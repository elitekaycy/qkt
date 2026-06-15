package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ConditionalAutocorr
import com.qkt.backtest.EquitySample
import com.qkt.backtest.MonteCarloSummary
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.Regime
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

    @Test
    fun `json report carries the monte carlo drawdown tail when available`() {
        val mc =
            MonteCarloSummary(
                simulations = 1000,
                finalEquityP5 = BigDecimal("-120.5"),
                finalEquityP25 = BigDecimal("10"),
                finalEquityP50 = BigDecimal("80.0"),
                finalEquityP75 = BigDecimal("200"),
                finalEquityP95 = BigDecimal("310.0"),
                maxDrawdownP5 = BigDecimal("-0.22"),
                maxDrawdownP95 = BigDecimal("-0.04"),
                probabilityNegativeFinal = BigDecimal("0.18"),
                equityFanByTradeIndex = emptyList(),
            )
        val r = report("0").copy(monteCarlo = mc)
        val res = BacktestResult(emptyList(), emptyList(), emptyMap(), r, emptyMap(), SampleCadence.TICK)
        val buf = ByteArrayOutputStream()
        ReportPrinter.print(res, ReportFormat.Json, PrintStream(buf), BrokerKind.PAPER)
        val json = buf.toString()
        assertThat(json).contains("\"monteCarlo\":{")
        assertThat(json).contains("\"simulations\":1000")
        assertThat(json).contains("\"maxDrawdownP95\":-0.04")
        assertThat(json).contains("\"maxDrawdownP5\":-0.22")
        assertThat(json).contains("\"finalEquityP95\":310.0")
        assertThat(json).contains("\"probabilityNegativeFinal\":0.18")
        // the per-trade equity fan is an HTML viz detail, not serialized to json
        assertThat(json).doesNotContain("equityFan")
    }

    @Test
    fun `json monte carlo is null when not enough trades`() {
        // default report has no monteCarlo (fewer than 30 trades would yield null upstream)
        val json = render(ReportFormat.Json, BrokerKind.PAPER)
        assertThat(json).contains("\"monteCarlo\":null")
    }

    @Test
    fun `daily metrics appear in text and json`() {
        val r =
            report("0").copy(
                maxDailyDrawdown = BigDecimal("0.04"),
                dailyPnL = mapOf(java.time.LocalDate.of(2026, 6, 4) to BigDecimal("12.50")),
            )
        val res = BacktestResult(emptyList(), emptyList(), emptyMap(), r, emptyMap(), SampleCadence.TICK)

        val text = ByteArrayOutputStream()
        ReportPrinter.print(res, ReportFormat.Text, PrintStream(text), BrokerKind.PAPER)
        assertThat(text.toString()).contains("Max daily DD:")

        val json = ByteArrayOutputStream()
        ReportPrinter.print(res, ReportFormat.Json, PrintStream(json), BrokerKind.PAPER)
        assertThat(json.toString()).contains("\"maxDailyDrawdown\":0.04")
        assertThat(json.toString()).contains("\"dailyPnL\":{\"2026-06-04\":12.50}")
    }

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
