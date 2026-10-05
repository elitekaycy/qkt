package com.qkt.connector.gateway

import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.Print
import com.qkt.marketdata.flow.SideVolumes
import com.qkt.marketdata.flow.TradeFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * A gateway account's live trade flow: per code, series and window length, the sums of the closed windows read so
 * far from `/v1/trades` or `/v1/liquidations` ([fetch]). A read never waits on the gateway: it answers what is
 * known (null for a window not read yet) and queues one background read, on [schedule], from the window asked or
 * the last one read up to the newest window closed at least [SETTLE_MS] ago, run once that window has settled. So
 * each window is read seconds after it closes, a whole bar before a rule may read it (`[1]` and further back). A
 * failed read is logged and retried on the next read. Served only on the account's own contracts ([listed]) and
 * for a series the gateway declares ([capabilities]).
 */
internal class GatewayTradeFlow(
    prefix: String,
    private val capabilities: () -> Collection<String>,
    private val fetch: (code: String, kind: FlowKind, fromMs: Long, toMs: Long) -> List<Print>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit = { delayMs, task ->
        READER.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    },
) : TradeFlow {
    private val log = LoggerFactory.getLogger(GatewayTradeFlow::class.java)
    private val symbols = GatewaySymbols(prefix)

    private class Series {
        val sums = ConcurrentHashMap<Long, SideVolumes>()

        @Volatile var fromMs: Long? = null

        @Volatile var toMs: Long? = null
        val queued = AtomicBoolean()
    }

    private data class Key(
        val code: String,
        val kind: FlowKind,
        val windowMs: Long,
    )

    private val series = ConcurrentHashMap<Key, Series>()

    /** Takes the codes of the subscription's [listing]. */
    fun listed(listing: List<WireInstrument>) = symbols.update(listing.map { it.code })

    override fun window(
        symbol: String,
        kind: FlowKind,
        windowMs: Long,
        startMs: Long,
    ): SideVolumes? {
        val code = symbols.venue(symbol) ?: return null
        val key = Key(code, kind, windowMs)
        val s = series.computeIfAbsent(key) { Series() }
        val known = s.sums[startMs]
        val newestClosed = Math.floorDiv(clock(), windowMs) * windowMs
        val from = s.fromMs
        val stale = from == null || startMs < from || (s.toMs ?: Long.MIN_VALUE) < newestClosed
        if (stale && s.queued.compareAndSet(false, true)) {
            val first = if (from == null || startMs < from) startMs else s.toMs ?: startMs
            schedule(maxOf(0, newestClosed + SETTLE_MS - clock())) { read(key, s, first) }
        }
        return known
    }

    private fun read(
        key: Key,
        s: Series,
        firstMs: Long,
    ) {
        try {
            val w = key.windowMs
            val endMs = Math.floorDiv(clock() - SETTLE_MS, w) * w
            if (firstMs >= endMs) return
            val byWindow = fetch(key.code, key.kind, firstMs, endMs).groupBy { Math.floorDiv(it.timeMs, w) * w }
            for (start in firstMs until endMs step w) s.sums[start] = SideVolumes.of(byWindow[start].orEmpty())
            val oldest = endMs - KEPT_WINDOWS * w
            s.sums.keys.removeIf { it < oldest }
            s.fromMs = maxOf(oldest, minOf(s.fromMs ?: firstMs, firstMs))
            s.toMs = maxOf(s.toMs ?: endMs, endMs)
        } catch (e: Exception) {
            log.warn("could not read the {} of {} from the gateway: {}", key.kind.capability, key.code, e.message)
        } finally {
            s.queued.set(false)
        }
    }

    override fun problem(
        symbol: String,
        kind: FlowKind,
    ): String? =
        if (kind.capability in capabilities()) {
            null
        } else {
            "its gateway does not serve ${kind.capability} (capability '${kind.capability}'); upgrade the gateway or its adapter"
        }

    private companion object {
        /** How long after a window closes its prints are read: the venue publishes a print within moments of it. */
        const val SETTLE_MS = 2_000L

        /** Windows kept per series, beyond any lookback a rule writes in practice. */
        const val KEPT_WINDOWS = 4_096L

        val READER =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "gateway-trade-flow").apply { isDaemon = true }
            }
    }
}
