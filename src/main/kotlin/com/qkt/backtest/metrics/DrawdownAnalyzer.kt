package com.qkt.backtest.metrics

import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquitySample
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Default cutoff for which drawdowns are worth reporting: episodes shallower than −1% are noise and
 * dropped. Shared by [DrawdownAnalyzer] and the streaming [DrawdownEpisodeAccumulator] so the report
 * and the live collector agree on what counts as a drawdown period.
 */
val DRAWDOWN_PERIOD_THRESHOLD: BigDecimal = BigDecimal("-0.01")

/**
 * Streaming detector of drawdown periods — peak → trough → recovery cycles — in an equity stream.
 * Feed `(timestamp, equity)` readings in order; read the periods so far at any point.
 *
 * Retains only the in-progress peak/trough state plus the list of *completed* episodes (one per
 * recovery), so memory scales with the number of drawdowns — not the number of samples. A drawdown
 * is kept only if its depth `(trough − peak) / peak` is at or below [threshold].
 *
 * e.g. 100 → 120 → 108 → 120 records one period: peak 120, trough 108, depth −10%, recovered.
 */
class DrawdownEpisodeAccumulator(
    private val threshold: BigDecimal,
) {
    private val mc = MathContext(16, RoundingMode.HALF_EVEN)
    private val closed = mutableListOf<DrawdownPeriod>()

    private var initialized = false
    private var peakTs = 0L
    private var peakEq = BigDecimal.ZERO
    private var troughTs = 0L
    private var troughEq = BigDecimal.ZERO
    private var inDrawdown = false
    private var lastTs = 0L

    fun accept(
        timestamp: Long,
        equity: BigDecimal,
    ) {
        if (!initialized) {
            peakTs = timestamp
            peakEq = equity
            troughTs = timestamp
            troughEq = equity
            lastTs = timestamp
            initialized = true
            return
        }
        lastTs = timestamp
        if (equity >= peakEq) {
            if (inDrawdown) {
                val depth = depth(peakEq, troughEq)
                if (depth <= threshold) {
                    closed.add(
                        DrawdownPeriod(
                            peakTimestamp = peakTs,
                            peakEquity = peakEq,
                            troughTimestamp = troughTs,
                            troughEquity = troughEq,
                            recoveryTimestamp = timestamp,
                            depthPct = depth,
                            durationMs = timestamp - peakTs,
                            ongoing = false,
                        ),
                    )
                }
                inDrawdown = false
            }
            peakTs = timestamp
            peakEq = equity
            troughTs = timestamp
            troughEq = equity
        } else {
            if (!inDrawdown || equity < troughEq) {
                troughTs = timestamp
                troughEq = equity
            }
            inDrawdown = true
        }
    }

    /**
     * Drawdown periods seen so far, deepest first. An unrecovered drawdown is included as an
     * `ongoing` period. Non-destructive — safe to call repeatedly mid-stream.
     */
    fun result(): List<DrawdownPeriod> {
        val out = closed.toMutableList()
        if (inDrawdown) {
            val depth = depth(peakEq, troughEq)
            if (depth <= threshold) {
                out.add(
                    DrawdownPeriod(
                        peakTimestamp = peakTs,
                        peakEquity = peakEq,
                        troughTimestamp = troughTs,
                        troughEquity = troughEq,
                        recoveryTimestamp = null,
                        depthPct = depth,
                        durationMs = lastTs - peakTs,
                        ongoing = true,
                    ),
                )
            }
        }
        return out.sortedBy { it.depthPct }
    }

    private fun depth(
        peak: BigDecimal,
        trough: BigDecimal,
    ): BigDecimal {
        if (peak.signum() == 0) return BigDecimal.ZERO
        return trough.subtract(peak).divide(peak, mc)
    }
}

object DrawdownAnalyzer {
    fun analyze(
        curve: List<EquitySample>,
        threshold: BigDecimal,
    ): List<DrawdownPeriod> {
        val acc = DrawdownEpisodeAccumulator(threshold)
        for (s in curve) acc.accept(s.timestamp, s.equity)
        return acc.result()
    }
}
