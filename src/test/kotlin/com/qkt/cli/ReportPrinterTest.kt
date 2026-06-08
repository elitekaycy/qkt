package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.EquitySample
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReportPrinterTest {
    private fun report(commissionPaid: String): PerformanceReport =
        PerformanceReport(
            realizedTotal = BigDecimal("100"),
            unrealizedTotal = BigDecimal.ZERO,
            totalPnL = BigDecimal("100"),
            tradeCount = 50,
            winRate = BigDecimal("0.6"),
            maxDrawdown = BigDecimal("-0.05"),
            profitFactor = BigDecimal("1.5"),
            avgWin = BigDecimal("3"),
            avgLoss = BigDecimal("-2"),
            largestWin = BigDecimal("10"),
            largestLoss = BigDecimal("-7"),
            maxConsecutiveLosses = 3,
            sharpeRatio = BigDecimal("1.2"),
            calmarRatio = BigDecimal("0.8"),
            equityCurve = listOf(EquitySample(0L, BigDecimal("100"))),
            commissionPaid = BigDecimal(commissionPaid),
        )

    private fun result(commissionPaid: String = "0"): BacktestResult =
        BacktestResult(
            trades = emptyList(),
            rejections = emptyList(),
            finalPositions = emptyMap(),
            global = report(commissionPaid),
            perStrategy = emptyMap(),
            cadence = SampleCadence.TICK,
        )

    private fun render(
        fmt: ReportFormat,
        brokerKind: BrokerKind,
        commissionPaid: String = "0",
    ): String {
        val buf = ByteArrayOutputStream()
        ReportPrinter.print(result(commissionPaid), fmt, PrintStream(buf), brokerKind)
        return buf.toString()
    }

    @Test
    fun `text report discloses execution assumptions and metric conventions`() {
        val out = render(ReportFormat.Text, BrokerKind.PAPER)
        // #336 — execution disclosure.
        assertThat(out).contains("Assumptions & conventions")
        assertThat(out).contains("paper — fills at mid price; no spread, no slippage modeled")
        // #338 — metric conventions + corrected Sharpe label.
        assertThat(out).contains("Sharpe (annual):")
        assertThat(out).doesNotContain("Sharpe (daily)")
        assertThat(out).contains("break-even trades excluded")
        assertThat(out).contains("NOT annualized")
    }

    @Test
    fun `commission line reflects whether commission was modeled`() {
        assertThat(render(ReportFormat.Text, BrokerKind.PAPER, commissionPaid = "0"))
            .contains("none modeled")
        assertThat(render(ReportFormat.Text, BrokerKind.PAPER, commissionPaid = "5.00"))
            .contains("Commission paid:  5.00")
    }

    @Test
    fun `mt5-sim discloses its richer fill model`() {
        assertThat(render(ReportFormat.Text, BrokerKind.MT5_SIM))
            .contains("mt5-sim — synthetic spread")
    }

    @Test
    fun `json report carries commissionPaid and executionModel`() {
        val json = render(ReportFormat.Json, BrokerKind.PAPER, commissionPaid = "5.00")
        assertThat(json).contains("\"commissionPaid\":5.00")
        assertThat(json).contains("\"executionModel\":\"paper\"")
    }
}
