package com.qkt.backtest

import com.qkt.backtest.metrics.DRAWDOWN_PERIOD_THRESHOLD
import com.qkt.backtest.metrics.DrawdownEpisodeAccumulator
import com.qkt.backtest.metrics.SharpeAccumulator
import com.qkt.risk.MaxDrawdownAccumulator
import java.math.BigDecimal

/**
 * Equity-curve performance metrics computed online, one sample at a time.
 *
 * Folds each `(timestamp, equity)` reading into running accumulators for max drawdown, Sharpe ratio,
 * and drawdown periods, so the full-resolution metrics are available without retaining the full
 * curve. This is what lets the collector bound its memory under a long tick run: the chart curve can
 * be decimated for display while these numbers stay exact — identical to computing them over the
 * whole curve in one pass.
 *
 * e.g. feed 100, 120, 90, 130 → [maxDrawdown] 0.25, a drawdown period peak 120 → trough 90, and a
 * Sharpe over the three returns.
 *
 * All read methods are non-destructive, so a report can be built mid-run as well as at the end.
 */
class EquityMetrics(
    drawdownThreshold: BigDecimal = DRAWDOWN_PERIOD_THRESHOLD,
) {
    private val maxDrawdown = MaxDrawdownAccumulator()
    private val sharpe = SharpeAccumulator()
    private val episodes = DrawdownEpisodeAccumulator(drawdownThreshold)

    private var firstTimestamp: Long? = null
    private var firstEquity: BigDecimal = BigDecimal.ZERO
    private var lastTimestamp: Long = 0L

    /** Number of readings folded in so far. */
    var count: Int = 0
        private set

    fun accept(
        timestamp: Long,
        equity: BigDecimal,
    ) {
        if (firstTimestamp == null) {
            firstTimestamp = timestamp
            firstEquity = equity
        }
        lastTimestamp = timestamp
        count++
        maxDrawdown.accept(equity)
        sharpe.accept(equity)
        episodes.accept(timestamp, equity)
    }

    fun maxDrawdown(): BigDecimal = maxDrawdown.value()

    fun sharpe(annualizationFactor: BigDecimal): BigDecimal? = sharpe.value(annualizationFactor)

    fun drawdownPeriods(): List<DrawdownPeriod> = episodes.result()

    /** Equity of the first reading, or zero if none — the starting point for Monte Carlo. */
    fun startingEquity(): BigDecimal = firstEquity

    /** Timestamp of the first reading, or null if none — for inferring sample spacing. */
    fun firstTimestamp(): Long? = firstTimestamp

    /** Timestamp of the most recent reading. */
    fun lastTimestamp(): Long = lastTimestamp
}
