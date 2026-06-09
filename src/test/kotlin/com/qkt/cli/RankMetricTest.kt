package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RankMetricTest {
    private fun resultWithSharpe(sharpe: BigDecimal?): BacktestResult =
        BacktestResult(
            trades = emptyList(),
            rejections = emptyList(),
            finalPositions = emptyMap(),
            global =
                PerformanceReport(
                    realizedTotal = BigDecimal.ZERO,
                    unrealizedTotal = BigDecimal.ZERO,
                    totalPnL = BigDecimal.ZERO,
                    tradeCount = 0,
                    winRate = BigDecimal.ZERO,
                    maxDrawdown = BigDecimal.ZERO,
                    profitFactor = null,
                    avgWin = BigDecimal.ZERO,
                    avgLoss = BigDecimal.ZERO,
                    largestWin = BigDecimal.ZERO,
                    largestLoss = BigDecimal.ZERO,
                    maxConsecutiveLosses = 0,
                    sharpeRatio = sharpe,
                    calmarRatio = null,
                    equityCurve = emptyList(),
                ),
            perStrategy = emptyMap(),
            cadence = SampleCadence.TICK,
        )

    @Test
    fun `fromFlag defaults to sharpe and rejects unknown`() {
        assertThat(RankMetric.fromFlag(null)).isEqualTo(RankMetric.SHARPE)
        assertThat(RankMetric.fromFlag("calmar")).isEqualTo(RankMetric.CALMAR)
        assertThatThrownBy { RankMetric.fromFlag("bogus") }.hasMessageContaining("unknown --rank")
    }

    @Test
    fun `score sends a null metric strictly last`() {
        val present = RankMetric.SHARPE.score(resultWithSharpe(BigDecimal("1.5")))
        val absent = RankMetric.SHARPE.score(resultWithSharpe(null))
        assertThat(present).isGreaterThan(absent)
    }
}
