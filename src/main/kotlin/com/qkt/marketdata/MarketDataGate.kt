package com.qkt.marketdata

import com.qkt.common.Clock
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Runtime judgment layer over live market data (FIA §1.3). Three checks, per symbol:
 *
 *  - **Stale quotes** — when no tick has arrived for [staleAgeMultiple] x the symbol's
 *    smoothed inter-tick gap (floored at [minStaleAgeMs]), the symbol is unhealthy and
 *    NEW order generation for it should be suppressed. Auto-resumes when data flows.
 *  - **Outlier ticks** — a price more than [outlierSigma] standard deviations from the
 *    short-window mean is rejected: it never updates indicators, marks, or triggers.
 *  - **Crossed books** (bid > ask) are treated as outliers.
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
) {
    private val log = LoggerFactory.getLogger(MarketDataGate::class.java)

    private class SymbolState {
        var lastSeenMs: Long = 0L
        var ewmaGapMs: Double = 0.0
        val window = ArrayDeque<Double>()
        var staleAlerted = false
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

        val crossed = tick.bid != null && tick.ask != null && tick.bid > tick.ask
        val price = tick.price.toDouble()
        val outlier = crossed || isOutlier(state, price)
        if (outlier) {
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
        state.window.addLast(price)
        while (state.window.size > WINDOW_SIZE) state.window.removeFirst()
        if (state.staleAlerted) {
            state.staleAlerted = false
            log.info("market data for {} healthy again", tick.symbol)
        }
        return Verdict.OK
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
        val window = state.window
        if (window.size < MIN_WINDOW_FOR_OUTLIER) return false
        val mean = window.sum() / window.size
        val variance = window.sumOf { (it - mean) * (it - mean) } / window.size
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
        }
        return healthy
    }

    private fun staleThresholdMs(state: SymbolState): Long {
        val fromGap = (state.ewmaGapMs * staleAgeMultiple).toLong()
        return maxOf(fromGap, minStaleAgeMs)
    }

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
        private const val WINDOW_SIZE = 64
        private const val MIN_WINDOW_FOR_OUTLIER = 16
        private const val EWMA_ALPHA = 0.1
        private const val MIN_RELATIVE_SIGMA = 0.002
        private const val OUTLIER_LOG_EVERY = 500L
    }
}

/**
 * Pre-trade rule companion to [MarketDataGate]: rejects NEW-exposure orders for a
 * symbol whose data is stale; risk-REDUCING orders (opposite side, no larger than the
 * open position, or close-by-ticket) still pass — frozen data is a reason to stop
 * adding risk, not a reason to trap the position.
 */
class MarketDataHealthRule(
    private val gate: MarketDataGate,
) : com.qkt.risk.RiskRule {
    override fun evaluate(
        request: com.qkt.execution.OrderRequest,
        positions: com.qkt.positions.PositionProvider,
    ): com.qkt.risk.Decision {
        if (gate.isHealthy(request.symbol)) return com.qkt.risk.Decision.Approve
        if (isRiskReducing(request, positions)) return com.qkt.risk.Decision.Approve
        return com.qkt.risk.Decision.Reject(
            "market data for ${request.symbol} is stale — new orders suppressed until data resumes",
        )
    }

    private fun isRiskReducing(
        request: com.qkt.execution.OrderRequest,
        positions: com.qkt.positions.PositionProvider,
    ): Boolean {
        if (request is com.qkt.execution.OrderRequest.Market && request.closesTicket != null) return true
        val net = positions.positionFor(request.symbol)?.quantity ?: return false
        if (net.signum() == 0) return false
        val opposes =
            (net.signum() > 0 && request.side == com.qkt.common.Side.SELL) ||
                (net.signum() < 0 && request.side == com.qkt.common.Side.BUY)
        return opposes && request.quantity <= net.abs()
    }
}
