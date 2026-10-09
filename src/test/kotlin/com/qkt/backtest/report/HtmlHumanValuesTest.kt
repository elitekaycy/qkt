package com.qkt.backtest.report

import com.qkt.accounting.AccountingSnapshot
import com.qkt.backtest.BacktestResult
import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquitySample
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import com.qkt.positions.Position
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HtmlHumanValuesTest {
    private fun drawdown(
        peakMs: Long,
        depth: String,
        durationMs: Long,
    ) = DrawdownPeriod(
        peakTimestamp = peakMs,
        peakEquity = BigDecimal("15000"),
        troughTimestamp = peakMs + 1000L,
        troughEquity = BigDecimal("14000"),
        recoveryTimestamp = peakMs + 2000L,
        depthPct = BigDecimal(depth),
        durationMs = durationMs,
        ongoing = false,
    )

    private fun result(
        trades: Int = 1,
        drawdowns: Int = 1,
    ): BacktestResult {
        val global =
            PerformanceReport(
                realizedTotal = BigDecimal.ZERO,
                unrealizedTotal = BigDecimal("4423.25"),
                totalPnL = BigDecimal("4423.25"),
                tradeCount = trades,
                winRate = BigDecimal.ZERO,
                maxDrawdown = BigDecimal("0.43244333"),
                profitFactor = null,
                avgWin = BigDecimal.ZERO,
                avgLoss = BigDecimal.ZERO,
                largestWin = BigDecimal.ZERO,
                largestLoss = BigDecimal.ZERO,
                maxConsecutiveLosses = 0,
                sharpeRatio = BigDecimal("0.63522786"),
                calmarRatio = BigDecimal("1.02285079"),
                equityCurve =
                    listOf(
                        EquitySample(1_727_686_800_000L, BigDecimal("10000")),
                        EquitySample(1_727_687_800_000L, BigDecimal("14423.25")),
                    ),
                drawdownPeriods = (0 until drawdowns).map { drawdown(1_727_686_800_000L + it, "-0.0$it", 16_613_100_000L) },
                commissionPaid = BigDecimal("51.20"),
            )
        return BacktestResult(
            trades = emptyList(),
            rejections = emptyList(),
            finalPositions =
                mapOf(
                    "BACKTEST:EURUSD" to
                        Position(symbol = "BACKTEST:EURUSD", quantity = BigDecimal.ONE, avgEntryPrice = BigDecimal("1.10")),
                ),
            global = global,
            perStrategy = emptyMap(),
            cadence = SampleCadence.TICK,
            accounting =
                AccountingSnapshot(
                    accountCurrency = "USD",
                    missingPolicy = "fail",
                    source = "market",
                    configuredSymbols = emptyMap(),
                    conversions = emptyList(),
                    warnings = emptyList(),
                ),
        )
    }

    private fun render(result: BacktestResult): String = HtmlReportWriter(HtmlReportConfig()).render(result)

    @Test
    fun `headline shows units with raw values on hover, not raw internals`() {
        val html = render(result())
        assertThat(html).contains("+4,423.25 USD")
        assertThat(html).contains("43.2%")
        assertThat(html).contains("0.64")
        assertThat(html).contains("class=\"value\" title=\"")
        assertThat(html).contains("title=\"0.43244333\"")
        assertThat(html).doesNotContain("0.43244333</div>")
        assertThat(html).doesNotContain("0.63522786</div>")
        assertThat(html).doesNotContain("4423.25000000")
    }

    @Test
    fun `verdict strip states open profit and warns on few trades`() {
        val html = render(result())
        assertThat(html).contains("Start 10,000.00 USD")
        assertThat(html).contains("end 14,423.25 USD")
        assertThat(html).contains("1 trade")
        assertThat(html).contains("still open")
        assertThat(html).contains("+4,423.25 USD")
        assertThat(html).contains("unrealized")
        assertThat(html).contains("Few trades")
    }

    @Test
    fun `drawdowns show dates and durations with the tail collapsed`() {
        val html = render(result(drawdowns = 12))
        assertThat(html).contains("2024-09-30")
        assertThat(html).contains("UTC</td>")
        assertThat(html).contains("192 days")
        assertThat(html).contains("2 smaller drawdowns")
        assertThat(html).contains("<details>")
        assertThat(html).doesNotContain("Duration ms")
        assertThat(html).doesNotContain("1727686800000</td>")
    }

    @Test
    fun `accounting lists only incurred costs with amounts`() {
        val html = render(result())
        assertThat(html).contains("Costs and adjustments")
        assertThat(html).contains("commission</td><td title=\"51.20\">+51.20 USD</td>")
        assertThat(html).doesNotContain("cost kinds")
    }

    @Test
    fun `chart axes never use scientific notation`() {
        val svg =
            SvgChart.lineChartWithUnderwater(
                curve =
                    listOf(
                        EquitySample(0L, BigDecimal("10000")),
                        EquitySample(1L, BigDecimal("15110")),
                    ),
                drawdowns = emptyList(),
                width = 1000,
                height = 360,
            )
        assertThat(svg).contains("15,110")
        assertThat(svg).doesNotContain("e+")
    }

    @Test
    fun `reproduce section carries the full command and sources with working copy buttons`() {
        val html =
            render(
                result().copy(
                    reproduction =
                        ReproductionInfo(
                            commandLine = "qkt backtest s.qkt --from 2024-09-30 --to 2024-10-01",
                            strategyFile = "s.qkt",
                            strategySource = "STRATEGY s VERSION 1\nWHEN a < b & c\nTHEN BUY x",
                            configFile = "qkt.config.yaml",
                            configSource = "starting_balance: 10000",
                            qktVersion = "0.55.1",
                            gitSha = "abc123",
                        ),
                ),
            )
        assertThat(html).contains("Reproduce this run")
        assertThat(html).contains("qkt backtest s.qkt --from 2024-09-30 --to 2024-10-01")
        assertThat(html).contains("STRATEGY s VERSION 1")
        assertThat(html).contains("WHEN a &lt; b &amp; c")
        assertThat(html).contains("starting_balance: 10000")
        val buttons = "onclick=\"qktCopy\\(".toRegex().findAll(html).count()
        assertThat(buttons).isEqualTo(3)
        assertThat(html).contains("navigator.clipboard")
        assertThat(html).contains("execCommand")
        assertThat(html).doesNotContain("<script src=")
    }

    @Test
    fun `no reproduce section without reproduction info`() {
        assertThat(render(result())).doesNotContain("Reproduce this run")
    }
}
