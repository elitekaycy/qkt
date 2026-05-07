package com.qkt.backtest.sweep

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SweepResultTest {
    private fun report(totalPnL: String): PerformanceReport =
        PerformanceReport(
            realizedTotal = Money.of(totalPnL),
            unrealizedTotal = Money.ZERO,
            totalPnL = Money.of(totalPnL),
            tradeCount = 0,
            winRate = Money.ZERO,
            maxDrawdown = Money.ZERO,
            profitFactor = null,
            avgWin = Money.ZERO,
            avgLoss = Money.ZERO,
            largestWin = Money.ZERO,
            largestLoss = Money.ZERO,
            maxConsecutiveLosses = 0,
            sharpeRatio = null,
            calmarRatio = null,
            equityCurve = emptyList(),
        )

    private fun result(totalPnL: String): BacktestResult =
        BacktestResult(
            trades = emptyList(),
            rejections = emptyList(),
            finalPositions = emptyMap(),
            global = report(totalPnL),
            perStrategy = emptyMap(),
            cadence = SampleCadence.CANDLE_CLOSE,
        )

    @Test
    fun `byLabel returns matching run`() {
        val sr =
            SweepResult(
                runs =
                    listOf(
                        SweepRun("a", 1, result("10")),
                        SweepRun("b", 2, result("20")),
                    ),
            )
        assertThat(sr.byLabel("a")?.config).isEqualTo(1)
        assertThat(sr.byLabel("b")?.config).isEqualTo(2)
    }

    @Test
    fun `byLabel returns null for unknown label`() {
        val sr = SweepResult(runs = listOf(SweepRun("a", 1, result("10"))))
        assertThat(sr.byLabel("missing")).isNull()
    }

    @Test
    fun `rankedBy sorts descending by score`() {
        val sr =
            SweepResult(
                runs =
                    listOf(
                        SweepRun("low", 1, result("5")),
                        SweepRun("high", 2, result("100")),
                        SweepRun("mid", 3, result("50")),
                    ),
            )
        val ranked = sr.rankedBy { it.global.totalPnL }
        assertThat(ranked.map { it.label }).containsExactly("high", "mid", "low")
    }

    @Test
    fun `rankedBy treats null score consumer as caller responsibility`() {
        val sr =
            SweepResult(
                runs =
                    listOf(
                        SweepRun("a", 1, result("10")),
                        SweepRun("b", 2, result("20")),
                    ),
            )
        val ranked = sr.rankedBy { it.global.sharpeRatio ?: BigDecimal.ZERO }
        assertThat(ranked.map { it.label }).containsExactlyInAnyOrder("a", "b")
    }
}
