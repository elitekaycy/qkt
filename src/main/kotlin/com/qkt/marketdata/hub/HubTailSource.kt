package com.qkt.marketdata.hub

import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * The live half of the hub binding: tails the store's journals and emits one tick per field.
 *
 * It is a [LiveTickSource] like the MT5 poller, and for the same reason -- the engine already has
 * exactly one way for the outside world to reach a strategy (the live tick feed's queue, the feed
 * thread, the single engine thread), and the parity contract rests on there being one such path.
 * A second queue for hub data would be a second place for ordering to go wrong.
 *
 * What it reads is the same bytes a backtest reads from the same store, which is what makes the
 * two modes agree about history rather than merely resemble each other.
 *
 * Failure posture is the guardian's: a malformed line is skipped and counted, a missing file is
 * retried, and a stale hub is reported through [onDisconnect] without ever ending the feed -- a
 * calendar going quiet must not stop price ticks. The strategy decides what a stale hub means by
 * gating on the `hub.health` dataset, the same way it gates on any other fact.
 */
class HubTailSource(
    private val root: Path,
    private val symbols: List<String>,
    private val policy: HubPolicy = HubPolicy(),
    private val pollIntervalMs: Long = 250L,
    private val staleAfterMs: Long = 900_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : LiveTickSource {
    private val log = LoggerFactory.getLogger(HubTailSource::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Per dataset: the fields the strategy reads, keyed by the symbol that delivers each one. */
    private val fieldsByDataset: Map<String, Map<String, String>> =
        symbols
            .mapNotNull { symbol -> runCatching { HubStreamSymbol.parse(symbol) }.getOrNull()?.let { symbol to it } }
            .groupBy({ it.second.dataset }, { it })
            .mapValues { (_, pairs) -> pairs.associate { (symbol, binding) -> binding.field to symbol } }

    /** Per dataset: the scope each requested symbol wants, so one journal serves several scopes. */
    private val scopesByDataset: Map<String, Map<String, String?>> =
        symbols
            .mapNotNull { symbol -> runCatching { HubStreamSymbol.parse(symbol) }.getOrNull()?.let { symbol to it } }
            .groupBy({ it.second.dataset }, { it })
            .mapValues { (_, pairs) -> pairs.associate { (symbol, binding) -> symbol to binding.scope } }

    override fun start(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
        onReconnect: () -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        // The boundary between history and "new" is fixed HERE, before start() returns, not on
        // the worker thread. Decided there, a record appended in the first milliseconds after
        // start would be read as history and never delivered -- a fact the store published while
        // the session was live, silently lost.
        val lastSeq = HashMap<String, Long>()
        for (dataset in fieldsByDataset.keys) {
            lastSeq[dataset] = HubJournal(root, dataset).readAll().maxOfOrNull { it.seq } ?: 0L
        }
        val worker =
            Thread({ loop(lastSeq, onTick, onError, onDisconnect, onReconnect) }, "qkt-hub-tail").apply {
                isDaemon = true
            }
        thread = worker
        worker.start()
    }

    override fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun loop(
        lastSeq: HashMap<String, Long>,
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
        onReconnect: () -> Unit,
    ) {
        // Everything already in the store at start is history, and history is the warmup
        // coordinator's job; starting at the current end means a restart never replays a day of
        // facts as if they had just been published.
        var stale = false
        while (running.get()) {
            try {
                val nowStale = isStale()
                if (nowStale && !stale) {
                    log.warn("hub store at {} is stale; holding last known facts", root)
                    onDisconnect()
                } else if (!nowStale && stale) {
                    onReconnect()
                }
                stale = nowStale
                for ((dataset, fields) in fieldsByDataset) {
                    val journal = HubJournal(root, dataset)
                    val since = lastSeq[dataset] ?: 0L
                    val fresh = journal.readAll().filter { it.seq > since }.sortedBy { it.seq }
                    for (record in fresh) {
                        emit(record, fields, scopesByDataset[dataset].orEmpty(), onTick)
                        lastSeq[dataset] = record.seq
                    }
                    if (journal.skipped > 0) {
                        log.warn("hub journal {} has {} unparseable line(s)", dataset, journal.skipped)
                    }
                }
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                // The tail must outlive any single bad read: report it, keep the feed alive.
                onError(e)
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun emit(
        record: HubRecord,
        fields: Map<String, String>,
        scopes: Map<String, String?>,
        onTick: (Tick) -> Unit,
    ) {
        if (policy.refuseDerived && record.isDerived) return
        for ((field, symbol) in fields) {
            val wanted = scopes[symbol]
            if (wanted != null && wanted != record.scope) continue
            val value = record.fields[field] ?: continue
            onTick(
                Tick(
                    symbol = symbol,
                    price = value.setScale(com.qkt.common.Money.SCALE, com.qkt.common.Money.ROUNDING),
                    timestamp = record.knownAt + policy.minLagMs,
                ),
            )
        }
    }

    private fun isStale(): Boolean {
        val heartbeat = root.resolve("heartbeat")
        if (!Files.isRegularFile(heartbeat)) return true
        return clock() - Files.getLastModifiedTime(heartbeat).toMillis() > staleAfterMs
    }
}
