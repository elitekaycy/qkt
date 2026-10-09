package com.qkt.backtest.report

import com.qkt.backtest.EquitySample
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HtmlHumanValuesTest : HtmlHumanValuesFixture() {
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
    fun `trades table colors sides and pnl with readable timestamps`() {
        fun rec(
            side: com.qkt.common.Side,
            realized: String,
        ) = com.qkt.backtest.TradeRecord(
            trade =
                com.qkt.execution.Trade(
                    orderId = "o",
                    symbol = "EURUSD",
                    price = BigDecimal("1.10"),
                    quantity = BigDecimal.ONE,
                    side = side,
                    timestamp = 1_727_686_800_000L,
                ),
            realized = BigDecimal(realized),
            strategyId = "s",
        )
        val html =
            HtmlTradesTable.render(
                listOf(rec(com.qkt.common.Side.BUY, "5"), rec(com.qkt.common.Side.SELL, "-2")),
                HtmlReportConfig(),
            )
        assertThat(html).contains("<span class=\"side-buy\">BUY</span>")
        assertThat(html).contains("<span class=\"side-sell\">SELL</span>")
        assertThat(html).contains("<td class=\"pos\">5</td>")
        assertThat(html).contains("<td class=\"neg\">-2</td>")
        assertThat(html).contains("2024-09-30 09:00 UTC")
    }
}
