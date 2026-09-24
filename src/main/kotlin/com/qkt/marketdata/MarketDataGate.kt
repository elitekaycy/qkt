package com.qkt.marketdata

import com.qkt.common.Clock
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Runtime judgment layer over live market data (FIA §1.3). Four checks, per symbol:
 *
 *  - **Stale quotes** — when no tick has arrived for [staleAgeMultiple] x the symbol's
 *    smoothed inter-tick gap (floored at [minStaleAgeMs]), the symbol is unhealthy and
 *    NEW order generation for it should be suppressed. Auto-resumes when data flows. A gap
 *    while the venue is out of session is expected: orders wait, but nothing alerts.
 *  - **Outlier ticks** — a price more than [outlierSigma] standard deviations from the mean
 *    of the last [outlierWindowMs] of prices is rejected. A short cluster at a coherent new
 *    level re-baselines the window so genuine gaps do not freeze marks and triggers
 *    indefinitely. The sample is bounded by AGE, not by tick count, so the band's width does
 *    not narrow when a feed starts delivering more ticks per second.
 *  - **Crossed books** (bid > ask) are treated as outliers.
 *  - **Clock skew** — when a fresh tick's broker timestamp is more than [maxClockSkewMs]
 *    from the local clock (#810), the feed's time base is wrong — usually a
 *    misconfigured broker `server_time_zone` shifting every timestamp by whole hours.
 *    A skewed clock mislabels bars, evaluates session windows at the wrong hours, and
 *    dates GTD expirations in the past, so NEW orders are suppressed until it realigns.
 *
 * This sits ABOVE the hard ingestion floor (zero/negative prices, #379): the floor
 * rejects the malformed, this layer rejects the implausible. Statistics run on Double —
 * this is judgment about data quality, not money math.
 */
class MarketDataGate(
    private val clock: Clock,
    private val staleAgeMultiple: Double = DEFAULT_STALE_AGE_MULTIPLE,
    private val minStaleAgeMs: Long = DEFAULT_MIN_STALE_AGE_MS,
    private val outlierSigma: Double = DEFAULT_OUTLIER_SIGMA,
    /**
     * Age of the price sample the outlier band is computed over. Bounding the window by time
     * rather than by tick count keeps the band's width independent of the feed's tick rate.
     */
    private val outlierWindowMs: Long = DEFAULT_OUTLIER_WINDOW_MS,
    private val maxClockSkewMs: Long = DEFAULT_MAX_CLOCK_SKEW_MS,
    /**
     * Invoked once per unhealthy transition with the [FeedFault] behind it; recovery permits a
     * later transition to alert again. Expected gaps (venue closed, scheduled break) never alert.
     */
    private val onUnhealthy: (symbol: String, reason: String, fault: FeedFault) -> Unit = { _, _, _ -> },
    /**
     * Invoked once when a symbol that alerted [onUnhealthy] is fully healthy again, with how long
     * the episode lasted since its first alert (e.g. `"fresh tick after stale"`, 184000).
     */
    private val onRecovered: (symbol: String, reason: String, unhealthyForMs: Long) -> Unit = { _, _, _ -> },
    /**
     * Whether the venue trades [symbol] at the given wall-clock instant (#1056). A tick whose
     * broker time trails the local clock while the venue is closed is the venue's last print,
     * not a skewed clock, and a quote gap while the venue is closed is not STALE (no alert;
     * new orders still wait for a fresh tick). Defaults to always open, as for 24/7 crypto.
     */
    private val inSession: (symbol: String, nowMs: Long) -> Boolean = { _, _ -> true },
    /**
     * Whether [symbol] is inside a venue-scheduled pause at the given time. A quote gap that
     * starts inside a pause is reported as PAUSED (info, no unhealthy alert) instead of STALE;
     * new orders are suppressed either way. Defaults to never paused.
     */
    private val scheduledBreak: (symbol: String, nowMs: Long) -> Boolean = { _, _ -> false },
) {
    private val log = LoggerFactory.getLogger(MarketDataGate::class.java)

    private val health =
        FeedHealthJudge(
            clock,
            staleAgeMultiple,
            minStaleAgeMs,
            maxClockSkewMs,
            onUnhealthy,
            onRecovered,
            inSession,
            scheduledBreak,
            log,
        )

    private val bySymbol: MutableMap<String, SymbolFeedState> = ConcurrentHashMap()

    /** Count of ticks rejected as outliers (crossed books included). */
    val outlierCount =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /** Verdict for one tick: feed it through, or reject it as an outlier. */
    enum class Verdict { OK, OUTLIER }

    fun observe(tick: Tick): Verdict {
        val state = bySymbol.getOrPut(tick.symbol) { SymbolFeedState() }
        val now = clock.now()

        // Every fresh tick re-measures the feed's time base: a broker timestamp hours
        // from the local clock is a time-zone misconfiguration, not latency.
        if (tick.timestamp > 0L) state.lastSkewMs = tick.timestamp - now

        val crossed = tick.bid != null && tick.ask != null && tick.bid > tick.ask
        val price = tick.price.toDouble()
        val outlier = crossed || state.isOutlier(price, now, outlierWindowMs, outlierSigma)
        if (outlier) {
            state.rejectedOutlierRun++
            if (!crossed && state.recordRebaselineCandidate(price)) {
                state.resetAt(price, now)
                state.staleAlerted = false
                state.touch(now)
                log.error(
                    "market data for {} re-baselined at {} after {} coherent outlier ticks",
                    tick.symbol,
                    tick.price.toPlainString(),
                    SymbolFeedState.REBASELINE_TICK_COUNT,
                )
                health.settleEpisode(tick.symbol, state)
                return Verdict.OK
            }
            if (crossed) {
                state.rebaselineCandidate = 0.0
                state.rebaselineCandidateCount = 0
            }
            val n = outlierCount.incrementAndGet()
            if (n == 1L || n % OUTLIER_LOG_EVERY == 0L) {
                log.warn(
                    "rejecting outlier tick #{} for {}: price={} (crossed={})",
                    n,
                    tick.symbol,
                    tick.price.toPlainString(),
                    crossed,
                )
            }
            // The clock of "data is flowing" still ticks — an outlier is data, just bad data.
            state.touch(now)
            return Verdict.OUTLIER
        }

        state.touch(now)
        state.push(price, now)
        state.clearRejectedOutliers()
        health.onFreshTick(tick.symbol, state)
        return Verdict.OK
    }

    /**
     * True when [symbol]'s data is fresh enough to generate NEW orders against.
     * Symbols never observed are healthy — the gate cannot judge what it hasn't seen,
     * and the notional cap separately rejects unpriceable orders.
     */
    fun isHealthy(symbol: String): Boolean {
        val state = bySymbol[symbol] ?: return true
        return health.isHealthy(symbol, state)
    }

    /** Symbols whose broker tick clock is out of tolerance, with the last measured skew in ms. */
    fun clockSkewedSymbols(): Map<String, Long> =
        bySymbol
            .filterValues { kotlin.math.abs(it.lastSkewMs) > maxClockSkewMs }
            .mapValues { (_, st) -> st.lastSkewMs }

    /** Symbols currently failing the staleness check, with their quote age in ms. */
    fun staleSymbols(): Map<String, Long> {
        val now = clock.now()
        return bySymbol
            .filterValues { it.lastSeenMs > 0L && now - it.lastSeenMs > health.staleThresholdMs(it) }
            .mapValues { (_, st) -> now - st.lastSeenMs }
    }

    companion object {
        const val DEFAULT_STALE_AGE_MULTIPLE: Double = 5.0
        const val DEFAULT_MIN_STALE_AGE_MS: Long = 10_000L
        const val DEFAULT_OUTLIER_SIGMA: Double = 6.0

        // Far above honest feed latency (poll interval + gateway round-trip, sparse
        // symbols gap ~15s), far below the whole-hour offsets a wrong time zone
        // produces — the smallest real misconfiguration is 3_600_000ms.
        const val DEFAULT_MAX_CLOCK_SKEW_MS: Long = 60_000L

        /** Widest real server-zone offset (UTC-12..UTC+14); a print older than this is a gap, not skew. */
        const val MAX_PLAUSIBLE_ZONE_OFFSET_MS: Long = 14L * 3_600_000L

        /** Sample age for the outlier band; preserves the span a 64-tick window covered at ~1 tick/s. */
        const val DEFAULT_OUTLIER_WINDOW_MS: Long = 64_000L

        private const val OUTLIER_LOG_EVERY = 500L
    }
}
