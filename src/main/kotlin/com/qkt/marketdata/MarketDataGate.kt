package com.qkt.marketdata

import com.qkt.common.Clock
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Runtime judgment layer over live market data (FIA §1.3). Four checks, per symbol:
 *
 *  - **Stale quotes** — when no tick has arrived for [staleAgeMultiple] x the symbol's
 *    smoothed inter-tick gap (floored at [minStaleAgeMs]), the symbol is unhealthy and
 *    NEW order generation for it should be suppressed. Auto-resumes when data flows.
 *  - **Outlier ticks** — a price more than [outlierSigma] standard deviations from the
 *    short-window mean is rejected. A short cluster at a coherent new level re-baselines
 *    the window so genuine gaps do not freeze marks and triggers indefinitely.
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
    private val maxClockSkewMs: Long = DEFAULT_MAX_CLOCK_SKEW_MS,
    /** Invoked once per unhealthy transition; recovery permits a later transition to alert again. */
    private val onUnhealthy: (symbol: String, reason: String) -> Unit = { _, _ -> },
    /**
     * Whether the venue trades [symbol] at the given wall-clock instant (#1056). A tick whose
     * broker time trails the local clock while the venue is closed is the venue's last print,
     * not a skewed clock; defaults to always open so the gate judges skew alone.
     */
    private val inSession: (symbol: String, nowMs: Long) -> Boolean = { _, _ -> true },
) {
    private val log = LoggerFactory.getLogger(MarketDataGate::class.java)

    private class SymbolState {
        var lastSeenMs: Long = 0L
        var ewmaGapMs: Double = 0.0

        // Primitive ring of the last WINDOW_SIZE prices, oldest at [windowHead] — the boxed
        // ArrayDeque<Double> allocated a wrapper per tick on the live hot path.
        val window = DoubleArray(WINDOW_SIZE)
        var windowHead = 0
        var windowSize = 0
        var staleAlerted = false
        var lastSkewMs = 0L
        var skewAlerted = false
        var closedAlerted = false
        var rejectedOutlierRun = 0
        var rebaselineCandidate = 0.0
        var rebaselineCandidateCount = 0

        fun push(price: Double) {
            if (windowSize < WINDOW_SIZE) {
                window[(windowHead + windowSize) % WINDOW_SIZE] = price
                windowSize++
            } else {
                window[windowHead] = price
                windowHead = (windowHead + 1) % WINDOW_SIZE
            }
        }

        fun resetAt(price: Double) {
            windowHead = 0
            windowSize = 0
            repeat(MIN_WINDOW_FOR_OUTLIER) { push(price) }
            clearRejectedOutliers()
        }

        fun clearRejectedOutliers() {
            rejectedOutlierRun = 0
            rebaselineCandidate = 0.0
            rebaselineCandidateCount = 0
        }
    }

    private val bySymbol: MutableMap<String, SymbolState> = ConcurrentHashMap()

    /** Count of ticks rejected as outliers (crossed books included). */
    val outlierCount =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /** Verdict for one tick: feed it through, or reject it as an outlier. */
    enum class Verdict { OK, OUTLIER }

    fun observe(tick: Tick): Verdict {
        val state = bySymbol.getOrPut(tick.symbol) { SymbolState() }
        val now = clock.now()

        // Every fresh tick re-measures the feed's time base: a broker timestamp hours
        // from the local clock is a time-zone misconfiguration, not latency.
        if (tick.timestamp > 0L) state.lastSkewMs = tick.timestamp - now

        val crossed = tick.bid != null && tick.ask != null && tick.bid > tick.ask
        val price = tick.price.toDouble()
        val outlier = crossed || isOutlier(state, price)
        if (outlier) {
            state.rejectedOutlierRun++
            if (!crossed && recordRebaselineCandidate(state, price)) {
                state.resetAt(price)
                state.staleAlerted = false
                touch(state, now)
                log.error(
                    "market data for {} re-baselined at {} after {} coherent outlier ticks",
                    tick.symbol,
                    tick.price.toPlainString(),
                    REBASELINE_TICK_COUNT,
                )
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
            touch(state, now)
            return Verdict.OUTLIER
        }

        touch(state, now)
        state.push(price)
        state.clearRejectedOutliers()
        if (state.staleAlerted) {
            state.staleAlerted = false
            log.info("market data for {} healthy again", tick.symbol)
        }
        if (state.closedAlerted && kotlin.math.abs(state.lastSkewMs) <= maxClockSkewMs) {
            state.closedAlerted = false
            log.info("market data for {}: fresh print after venue gap; healthy again", tick.symbol)
        }
        if (state.skewAlerted && kotlin.math.abs(state.lastSkewMs) <= maxClockSkewMs) {
            state.skewAlerted = false
            log.info(
                "market data for {} clock realigned: skew {}ms within {}ms tolerance",
                tick.symbol,
                state.lastSkewMs,
                maxClockSkewMs,
            )
        }
        return Verdict.OK
    }

    private fun recordRebaselineCandidate(
        state: SymbolState,
        price: Double,
    ): Boolean {
        val tolerance = maxOf(kotlin.math.abs(state.rebaselineCandidate), 1.0) * REBASELINE_CLUSTER_TOLERANCE
        if (state.rebaselineCandidateCount == 0 || kotlin.math.abs(price - state.rebaselineCandidate) > tolerance) {
            state.rebaselineCandidate = price
            state.rebaselineCandidateCount = 1
        } else {
            state.rebaselineCandidateCount++
            state.rebaselineCandidate +=
                (price - state.rebaselineCandidate) / state.rebaselineCandidateCount
        }
        return state.rebaselineCandidateCount >= REBASELINE_TICK_COUNT
    }

    private fun touch(
        state: SymbolState,
        now: Long,
    ) {
        if (state.lastSeenMs > 0L) {
            val gap = (now - state.lastSeenMs).toDouble()
            state.ewmaGapMs =
                if (state.ewmaGapMs == 0.0) gap else EWMA_ALPHA * gap + (1 - EWMA_ALPHA) * state.ewmaGapMs
        }
        state.lastSeenMs = now
    }

    private fun isOutlier(
        state: SymbolState,
        price: Double,
    ): Boolean {
        val n = state.windowSize
        if (n < MIN_WINDOW_FOR_OUTLIER) return false
        // Two passes in oldest-to-newest order, matching the deque version's summation order
        // exactly so the double math is unchanged.
        val window = state.window
        val head = state.windowHead
        var sum = 0.0
        for (k in 0 until n) sum += window[(head + k) % WINDOW_SIZE]
        val mean = sum / n
        var ssd = 0.0
        for (k in 0 until n) {
            val d = window[(head + k) % WINDOW_SIZE] - mean
            ssd += d * d
        }
        val variance = ssd / n
        val sigma = kotlin.math.sqrt(variance)
        // A flat window (sigma ~ 0) cannot judge deviation meaningfully — use a small
        // relative floor so a constant-price series doesn't flag the first real move.
        val effectiveSigma = maxOf(sigma, mean.coerceAtLeast(1.0) * MIN_RELATIVE_SIGMA)
        return kotlin.math.abs(price - mean) > outlierSigma * effectiveSigma
    }

    /**
     * True when [symbol]'s data is fresh enough to generate NEW orders against.
     * Symbols never observed are healthy — the gate cannot judge what it hasn't seen,
     * and the notional cap separately rejects unpriceable orders.
     */
    fun isHealthy(symbol: String): Boolean {
        val state = bySymbol[symbol] ?: return true
        if (state.lastSeenMs == 0L) return true
        if (state.rejectedOutlierRun > 0) {
            if (!state.staleAlerted) {
                state.staleAlerted = true
                log.error(
                    "market data for {} UNHEALTHY: {} consecutive outlier tick(s) rejected — suppressing new orders",
                    symbol,
                    state.rejectedOutlierRun,
                )
                onUnhealthy(symbol, "${state.rejectedOutlierRun} consecutive outlier tick(s) rejected")
            }
            return false
        }
        if (kotlin.math.abs(state.lastSkewMs) > maxClockSkewMs) {
            // A print that trails the local clock by more than any plausible server-zone
            // offset, or while the venue is closed, is the venue's last tick before a
            // gap — a weekend, a holiday, a symbol that opens later than its peers. Not
            // a clock problem: keep new orders suppressed (nothing to trade against),
            // say so once at INFO, and let the first fresh tick clear it (#1056).
            val now = clock.now()
            val lastPrint =
                state.lastSkewMs < 0L &&
                    (-state.lastSkewMs > MAX_PLAUSIBLE_ZONE_OFFSET_MS || !inSession(symbol, now))
            if (lastPrint) {
                if (!state.closedAlerted) {
                    state.closedAlerted = true
                    log.info(
                        "market data for {}: venue closed — last print {}ms old; new orders wait for a fresh tick",
                        symbol,
                        -state.lastSkewMs,
                    )
                }
                return false
            }
            if (!state.skewAlerted) {
                state.skewAlerted = true
                log.error(
                    "market data for {} CLOCK-SKEWED: broker tick time {}ms from local clock " +
                        "exceeds {}ms tolerance — suppressing new orders (check server_time_zone)",
                    symbol,
                    state.lastSkewMs,
                    maxClockSkewMs,
                )
                onUnhealthy(symbol, "broker tick clock skew ${state.lastSkewMs}ms exceeds ${maxClockSkewMs}ms")
            }
            return false
        }
        val threshold = staleThresholdMs(state)
        val age = clock.now() - state.lastSeenMs
        val healthy = age <= threshold
        if (!healthy && !state.staleAlerted) {
            state.staleAlerted = true
            log.error(
                "market data for {} STALE: age {}ms exceeds threshold {}ms — suppressing new orders",
                symbol,
                age,
                threshold,
            )
            onUnhealthy(symbol, "quote age ${age}ms exceeds ${threshold}ms threshold")
        }
        return healthy
    }

    private fun staleThresholdMs(state: SymbolState): Long {
        val fromGap = (state.ewmaGapMs * staleAgeMultiple).toLong()
        return maxOf(fromGap, minStaleAgeMs)
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
            .filterValues { it.lastSeenMs > 0L && now - it.lastSeenMs > staleThresholdMs(it) }
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
        private const val WINDOW_SIZE = 64
        private const val MIN_WINDOW_FOR_OUTLIER = 16
        private const val EWMA_ALPHA = 0.1
        private const val MIN_RELATIVE_SIGMA = 0.002
        private const val REBASELINE_CLUSTER_TOLERANCE = 0.002
        private const val REBASELINE_TICK_COUNT = 3
        private const val OUTLIER_LOG_EVERY = 500L
    }
}

/**
 * Pre-trade rule companion to [MarketDataGate]: rejects NEW-exposure orders for a
 * symbol whose data is unhealthy (stale quotes or a skewed broker clock);
 * risk-REDUCING orders (opposite side, no larger than the open position, or
 * close-by-ticket) still pass — bad data is a reason to stop adding risk, not a
 * reason to trap the position.
 */
class MarketDataHealthRule(
    private val gate: MarketDataGate,
) : com.qkt.risk.RiskRule {
    override fun evaluate(
        request: com.qkt.execution.OrderRequest,
        positions: com.qkt.positions.PositionProvider,
    ): com.qkt.risk.Decision {
        if (gate.isHealthy(request.symbol)) return com.qkt.risk.Decision.Approve
        if (com.qkt.risk.isRiskReducing(request, positions)) return com.qkt.risk.Decision.Approve
        return com.qkt.risk.Decision.Reject(
            "market data for ${request.symbol} is unhealthy (stale or clock-skewed) — " +
                "new orders suppressed until data recovers",
        )
    }
}
