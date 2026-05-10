package com.qkt.backtest.metrics

import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquitySample
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

object DrawdownAnalyzer {
    private val mc = MathContext(16, RoundingMode.HALF_EVEN)

    fun analyze(
        curve: List<EquitySample>,
        threshold: BigDecimal,
    ): List<DrawdownPeriod> {
        if (curve.size < 2) return emptyList()

        val out = mutableListOf<DrawdownPeriod>()
        var peakTs = curve[0].timestamp
        var peakEq = curve[0].equity
        var troughTs = peakTs
        var troughEq = peakEq
        var inDrawdown = false

        for (i in 1 until curve.size) {
            val s = curve[i]
            if (s.equity >= peakEq) {
                if (inDrawdown) {
                    val depth = depth(peakEq, troughEq)
                    if (depth <= threshold) {
                        out.add(
                            DrawdownPeriod(
                                peakTimestamp = peakTs,
                                peakEquity = peakEq,
                                troughTimestamp = troughTs,
                                troughEquity = troughEq,
                                recoveryTimestamp = s.timestamp,
                                depthPct = depth,
                                durationMs = s.timestamp - peakTs,
                                ongoing = false,
                            ),
                        )
                    }
                    inDrawdown = false
                }
                peakTs = s.timestamp
                peakEq = s.equity
                troughTs = s.timestamp
                troughEq = s.equity
            } else {
                if (!inDrawdown || s.equity < troughEq) {
                    troughTs = s.timestamp
                    troughEq = s.equity
                }
                inDrawdown = true
            }
        }

        if (inDrawdown) {
            val depth = depth(peakEq, troughEq)
            if (depth <= threshold) {
                val end = curve.last().timestamp
                out.add(
                    DrawdownPeriod(
                        peakTimestamp = peakTs,
                        peakEquity = peakEq,
                        troughTimestamp = troughTs,
                        troughEquity = troughEq,
                        recoveryTimestamp = null,
                        depthPct = depth,
                        durationMs = end - peakTs,
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
