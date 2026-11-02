package com.qkt.observe.insights

import com.qkt.broker.Broker
import com.qkt.pnl.PositionMarks
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Polls each broker's account state, open position tickets, and deal history on its own
 * daemon thread and offers the resulting envelopes to the insights sink. Never runs on
 * the engine thread — a slow or dead gateway delays only this poller. Restart-safe:
 * deal envelope ids are deterministic, so re-backfilling [backfillDays] of history
 * after a restart dedupes at the collector instead of double-counting.
 *
 * e.g. one cycle for EXNESS emits one "state.account", one "state.positions" (full
 * replace), one "state.orders" (full replace), and — when the DEAL family is enabled —
 * one "broker.deal" per locally-attributed deal booked since the previous cycle. Pollers
 * without deployed strategy ids keep account-level behavior and emit all deals.
 *
 * Deal history is fetched once per venue account per cycle, not once per broker: profile
 * brokers sharing one MT5 login all see the same account-wide history, so the first
 * broker of each account owns the fetch and the emitted envelopes (e.g. 22 profiles on
 * one login -> one deals request per cycle instead of 22 duplicates).
 */
class BrokerStatePoller(
    private val brokers: List<Broker>,
    private val sink: InsightsSink,
    /** Ticket → strategy mirror maintained by the session; this poller only reads it. */
    private val attribution: TicketAttribution,
    /** Currently-deployed strategy ids, for comment-prefix fallback attribution. */
    private val deployedIds: () -> Collection<String>,
    /**
     * This session's strategy ids in the exact form the dashboard keys on (the same
     * ids [InsightsTranslate.strategyStarted] emits), announced each cycle as the live
     * roster. Distinct from [deployedIds], which is the daemon-wide attribution set in
     * DSL-name form. Empty by default → no roster is announced (older wiring).
     */
    private val rosterIds: () -> Collection<String> = { emptyList() },
    private val pollIntervalMs: Long = 10_000L,
    /** How far back the first cycle fetches deals; later cycles fetch only new ones. */
    private val backfillDays: Long = 30L,
    /**
     * Whether deal history is emitted. Gated by the DEAL insights family — the poller
     * itself rides STATE, so a config enabling state but not deal gets account,
     * positions, and pending orders without the deal stream.
     */
    private val emitDeals: Boolean = true,
    /**
     * How far behind "now" the deal watermark may trail on a quiet account. Without this
     * a dealless account never advances its watermark and re-fetches the whole backfill
     * window every cycle; with it, late-booked deals within the grace are still caught.
     */
    private val dealGraceMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Deal history is account-wide, but every live session runs its own poller; sharing
     * one fetch per account per cycle across sessions keeps N sessions from issuing N
     * identical `history_deals_get` calls. Each session still attributes from its own map.
     */
    private val sharedDeals: SharedDealFetch = SharedDealFetch(),
    /** Cadence while every broker reports its market closed; venue state cannot change. */
    private val closedPollIntervalMs: Long = 5 * 60_000L,
    /**
     * Per-strategy equity envelopes ([InsightsTranslate.equitySnapshot]) sampled once per
     * cycle from the session's PnL view. The store populates `equity_snapshots` only from
     * this type (#1073); the session supplies a reader that snapshots on the engine thread.
     */
    private val strategyEquity: () -> List<InsightsEnvelope> = { emptyList() },
    /** The venue and instrument facts each envelope states ([InsightsVenues]). */
    private val venues: InsightsVenues = InsightsVenues(),
    /** Entry brackets and risk by position id, from the session's fills; null when there are none. */
    private val fills: InsightsFillFacts? = null,
    /** Values a position the venue states no mark or profit for, from the session's quotes ([VenueStateCycle]). */
    private val marks: PositionMarks? = null,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(BrokerStatePoller::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastClosedPollMs: Long? = null

    private val cycle =
        VenueStateCycle(
            this,
            sink::offer,
            attribution,
            deployedIds,
            venues,
            fills,
            emitDeals,
            backfillDays,
            dealGraceMs,
            sharedDeals,
            clock,
            marks,
        )
    private val consecutiveFailures = mutableMapOf<Broker, Int>()

    /** Starts the polling thread. Idempotent — a second call is a no-op. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread =
            Thread({
                while (running.get()) {
                    pollOnce()
                    try {
                        Thread.sleep(pollIntervalMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }, "qkt-broker-state").apply {
                isDaemon = true
                start()
            }
    }

    /**
     * One full pass over every broker. A broker fault is logged once per
     * consecutive-failure streak and never kills the thread.
     */
    internal fun pollOnce() {
        val now = clock()
        if (brokers.none { it.marketOpen(now) }) {
            val last = lastClosedPollMs
            if (last != null && now - last < closedPollIntervalMs) return
            lastClosedPollMs = now
        } else {
            lastClosedPollMs = null
        }
        // Roster first, so the collector retires ids a prior topology left behind whatever any broker's
        // fetch does; each session announces its own ids and the collector unions them across sessions.
        rosterIds().takeIf { it.isNotEmpty() }?.let { sink.offer(InsightsTranslate.instanceRoster(clock(), it)) }
        // Model-side equity per strategy rides the same cadence as venue truth; a failed
        // read (engine busy, session stopping) skips this cycle rather than the poll.
        runCatching { strategyEquity() }
            .onSuccess { samples -> samples.forEach(sink::offer) }
            .onFailure { e -> log.warn("[insights] strategy equity sample failed: {}", e.message) }
        // Accounts whose deals were already fetched this cycle — the first broker of each
        // account owns the fetch, the rest skip it (deal history is account-wide).
        val dealsFetched = mutableSetOf<String>()
        for (broker in brokers) {
            runCatching { cycle.poll(broker, dealsFetched) }
                .onSuccess { consecutiveFailures[broker] = 0 }
                .onFailure { e ->
                    val failures = (consecutiveFailures[broker] ?: 0) + 1
                    consecutiveFailures[broker] = failures
                    if (failures == 1) {
                        log.warn("[insights] broker state poll failed for {}: {}", broker.name, e.message)
                    }
                }
        }
    }

    /**
     * Stops the polling thread, then polls every broker until one pass reads each completely (account and
     * deals), retrying every [retryMs] for up to [timeoutMs]: a stopping session's last fills (its
     * flatten's closes) reach the collector before the session lets go of its venues, even through a
     * gateway that is timing out. Each attempt is logged when it fails.
     */
    fun drainAndClose(
        timeoutMs: Long = DRAIN_TIMEOUT_MS,
        retryMs: Long = DRAIN_RETRY_MS,
    ) {
        val polling = thread
        close()
        polling?.join(timeoutMs)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val fetched = mutableSetOf<String>()
            // A broker with no venue account (paper) is complete as soon as its pass ran.
            val complete =
                brokers.map { broker ->
                    runCatching { cycle.poll(broker, fetched) || !broker.supportsAccountEquity }
                        .onFailure { e -> log.warn("[insights] final poll of {} failed: {}", broker.name, e.message) }
                        .getOrDefault(false)
                }
            if (complete.all { it } || System.nanoTime() >= deadline) return
            try {
                Thread.sleep(retryMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** Stops the polling thread, waiting up to two seconds for the current cycle. */
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread?.join(2_000)
        thread = null
    }

    private companion object {
        const val DRAIN_TIMEOUT_MS: Long = 20_000L
        const val DRAIN_RETRY_MS: Long = 1_000L
    }
}
