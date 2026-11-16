package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.backtest.RunawayBreakerReport
import com.qkt.risk.RunawayBreakerRule
import com.qkt.risk.RunawayBreakerTrip
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReportPrinterDefaultsTest : ReportPrinterFixture() {
    private fun concise(result: com.qkt.backtest.BacktestResult): String {
        val buffer = ByteArrayOutputStream()
        ConciseReportPrinter.print(result, PrintStream(buffer), BrokerKind.PAPER)
        return buffer.toString()
    }

    @Test
    fun `the concise report names the live behaviour an observe-only breaker would have had`() {
        val trip = RunawayBreakerTrip(1_700_000_000_000L, "fast", RunawayBreakerRule.ROUND_TRIPS, 11, 10, 600_000L)
        val observed = RunawayBreakerReport(false, 10, 600_000L, 5, 60_000L, listOf(trip))

        assertThat(concise(result().copy(runawayBreaker = observed)))
            .contains("LIVE BEHAVIOR WARNING: the runaway breaker would have halted this strategy")
        assertThat(
            concise(result().copy(runawayBreaker = observed.copy(trips = emptyList()))),
        ).doesNotContain("LIVE BEHAVIOR")
        assertThat(
            concise(result().copy(runawayBreaker = observed.copy(enforceLiveBreakers = true))),
        ).doesNotContain("LIVE BEHAVIOR")
    }

    @Test
    fun `the json report carries dividends only when some were paid`() {
        fun json(dividends: String): String {
            val buffer = ByteArrayOutputStream()
            val withDividends = result().copy(global = report("0").copy(dividendsPaid = BigDecimal(dividends)))
            ReportPrinter.print(withDividends, ReportFormat.Json, PrintStream(buffer), BrokerKind.PAPER)
            return buffer.toString()
        }

        assertThat(json("12.5")).contains("\"dividendsPaid\":12.5")
        assertThat(json("0")).doesNotContain("dividendsPaid")
    }
}
