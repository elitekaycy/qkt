package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal

class ReportFormatPerStrategyTest {
    private fun report(pnl: String, trades: Int) =
        PerformanceReport(
            realizedTotal = BigDecimal(pnl),
            unrealizedTotal = BigDecimal.ZERO,
            totalPnL = BigDecimal(pnl),
            tradeCount = trades,
            winRate = BigDecimal("0.50"),
            maxDrawdown = BigDecimal("0.10"),
            profitFactor = BigDecimal("1.5"),
            avgWin = BigDecimal("10"),
            avgLoss = BigDecimal("-5"),
            largestWin = BigDecimal("20"),
            largestLoss = BigDecimal("-8"),
            maxConsecutiveLosses = 2,
            sharpeRatio = BigDecimal("1.1"),
            calmarRatio = BigDecimal("0.8"),
            equityCurve = emptyList(),
            sortinoRatio = BigDecimal("1.4"),
            turnover = BigDecimal("3.0"),
        )

    private fun result() =
        BacktestResult(
            trades = emptyList(),
            rejections = emptyList(),
            finalPositions = emptyMap(),
            global = report("100", 4),
            perStrategy = mapOf("alpha" to report("70", 2), "beta" to report("30", 2)),
            cadence = SampleCadence.TICK,
        )

    private fun render(
        result: BacktestResult,
        fmt: ReportFormat,
    ): String {
        val baos = ByteArrayOutputStream()
        ReportPrinter.print(result, fmt, PrintStream(baos), BrokerKind.PAPER)
        return baos.toString()
    }

    @Test
    fun `text output lists each strategy`() {
        val out = render(result(), ReportFormat.Text)
        assertThat(out).contains("Per-strategy")
        assertThat(out).contains("alpha")
        assertThat(out).contains("beta")
    }

    @Test
    fun `text output shows global sortino and turnover`() {
        val out = render(result(), ReportFormat.Text)
        assertThat(out).contains("Sortino (annual): 1.4")
        assertThat(out).contains("Turnover (x cap): 3.0")
    }

    @Test
    fun `json output contains a perStrategy object keyed by id`() {
        val out = render(result(), ReportFormat.Json)
        assertThat(out).contains("\"perStrategy\":{")
        assertThat(out).contains("\"alpha\":{")
        assertThat(out).contains("\"beta\":{")
        assertThat(out).contains("\"sortinoRatio\":1.4")
        assertThat(out).contains("\"turnover\":3.0")
    }

    @Test
    fun `single-strategy run omits the per-strategy table in text`() {
        val single = result().copy(perStrategy = mapOf("only" to report("100", 4)))
        assertThat(render(single, ReportFormat.Text)).doesNotContain("Per-strategy")
    }
}
