package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.events.RiskEvent
import com.qkt.positions.Position
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConciseReportPrinterTest : ReportPrinterFixture() {
    private fun renderDefault(): String {
        val buf = ByteArrayOutputStream()
        val res =
            result().copy(
                global =
                    report("0").copy(
                        maxDrawdown = BigDecimal("0.05"),
                        maxDailyDrawdown = BigDecimal("0.0061"),
                    ),
            )
        ReportPrinter.print(res, ReportFormat.Text, PrintStream(buf), BrokerKind.PAPER)
        return buf.toString()
    }

    @Test
    fun `default view fits on one screen with units and no raw fractions`() {
        val text = renderDefault()
        assertThat(text.lines().size).isLessThanOrEqualTo(22)
        assertThat(text).contains("Profit")
        assertThat(text).contains("+100.00")
        assertThat(text).contains("(+100.0%)")
        assertThat(text).contains("Round trips")
        assertThat(text).contains("0 closed")
        assertThat(text).contains("Open at the end")
        assertThat(text).contains("none")
        assertThat(text).contains("Worst drop")
        assertThat(text).contains("5.0%")
        assertThat(text).contains("Worst day")
        assertThat(text).contains("0.6%")
        assertThat(text).contains("Sharpe ratio")
        assertThat(text).contains("return per unit of risk")
        assertThat(text).contains("none modeled (no spread, commission or swap)")
        assertThat(text).contains("Try --broker mt5-sim for a realistic fill model.")
        assertThat(text).doesNotContain("Replay inputs")
        assertThat(text).doesNotContain("Assumptions & conventions")
        assertThat(text).doesNotContain("Final realized:")
        assertThat(text).doesNotContain("0.00000000")
    }

    @Test
    fun `open positions at the end are stated with their worth`() {
        val buf = ByteArrayOutputStream()
        val res =
            result().copy(
                global = report("0").copy(unrealizedTotal = BigDecimal("4423.25")),
                finalPositions =
                    mapOf(
                        "MT5:EURUSD" to
                            Position(
                                symbol = "MT5:EURUSD",
                                quantity = BigDecimal("1.50"),
                                avgEntryPrice = BigDecimal("1.10"),
                            ),
                    ),
            )
        ReportPrinter.print(res, ReportFormat.Text, PrintStream(buf), BrokerKind.PAPER)
        assertThat(buf.toString()).contains("MT5:EURUSD +1.5")
        assertThat(buf.toString()).contains("+4,423.25")
    }

    @Test
    fun `a halted run says trading stopped partway`() {
        val buf = ByteArrayOutputStream()
        val res = result().copy(halts = listOf(RiskEvent.Halted(reason = "max drawdown", strategyId = null)))
        ReportPrinter.print(res, ReportFormat.Text, PrintStream(buf), BrokerKind.PAPER)
        assertThat(buf.toString()).contains("1 halt(s); trading stopped partway")
    }

    @Test
    fun `verbose keeps todays full output`() {
        val buf = ByteArrayOutputStream()
        ReportPrinter.print(result(), ReportFormat.Text, PrintStream(buf), BrokerKind.PAPER, verbose = true)
        val text = buf.toString()
        assertThat(text).contains("Trades:")
        assertThat(text).contains("Final realized:")
        assertThat(text).contains("Assumptions & conventions")
    }
}
