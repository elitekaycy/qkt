package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ConditionalAutocorr
import com.qkt.backtest.Regime
import com.qkt.backtest.SampleCadence
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReportPrinterAutocorrelationTest : ReportPrinterFixture() {
    private fun autocorr(): ConditionalAutocorr =
        ConditionalAutocorr(
            perHour = mapOf(13 to BigDecimal("-1.0"), 14 to BigDecimal("0.42")),
            perRegime = mapOf(Regime.HIGH to BigDecimal("0.31"), Regime.LOW to BigDecimal("-0.05")),
            hourCounts = mapOf(13 to 120, 14 to 90),
            regimeCounts = mapOf(Regime.HIGH to 600, Regime.LOW to 600),
        )

    @Test
    fun `json report carries the conditional autocorrelation object keyed by symbol`() {
        val res =
            BacktestResult(
                emptyList(),
                emptyList(),
                emptyMap(),
                report("0"),
                emptyMap(),
                SampleCadence.CANDLE_CLOSE,
                conditionalAutocorr = mapOf("XAUUSD" to autocorr()),
            )
        val buf = ByteArrayOutputStream()
        ReportPrinter.print(res, ReportFormat.Json, PrintStream(buf), BrokerKind.PAPER)
        val json = buf.toString()
        assertThat(json).contains("\"conditionalAutocorr\":{\"XAUUSD\":{")
        assertThat(json).contains("\"perHour\":{\"13\":-1.0,\"14\":0.42}")
        assertThat(json).contains("\"perRegime\":{\"high\":0.31,\"low\":-0.05}")
        assertThat(json).contains("\"hourCounts\":{\"13\":120,\"14\":90}")
        assertThat(json).contains("\"regimeCounts\":{\"high\":600,\"low\":600}")
    }

    @Test
    fun `json conditional autocorrelation is an empty object when absent`() {
        val json = render(ReportFormat.Json, BrokerKind.PAPER)
        assertThat(json).contains("\"conditionalAutocorr\":{}")
    }

    @Test
    fun `text report renders the conditional autocorrelation block`() {
        val res =
            BacktestResult(
                emptyList(),
                emptyList(),
                emptyMap(),
                report("0"),
                emptyMap(),
                SampleCadence.CANDLE_CLOSE,
                conditionalAutocorr = mapOf("XAUUSD" to autocorr()),
            )
        val buf = ByteArrayOutputStream()
        ReportPrinter.print(res, ReportFormat.Text, PrintStream(buf), BrokerKind.PAPER)
        val text = buf.toString()
        assertThat(text).contains("Lag-1 return autocorrelation")
        assertThat(text).contains("high = |return| >= median; buckets with <3 returns omitted")
        assertThat(text).contains("XAUUSD")
        assertThat(text).contains("13  -1.0")
        assertThat(text).contains("high  0.31")
    }

    @Test
    fun `text report omits the autocorrelation block when no bucket is populated`() {
        val text = render(ReportFormat.Text, BrokerKind.PAPER)
        assertThat(text).doesNotContain("Lag-1 return autocorrelation")
    }
}
