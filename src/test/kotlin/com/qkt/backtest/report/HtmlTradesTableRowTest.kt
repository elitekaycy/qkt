package com.qkt.backtest.report

import com.qkt.backtest.TradeRecord
import com.qkt.common.Side
import com.qkt.execution.Trade
import com.qkt.positions.Position
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HtmlTradesTableRowTest {
    private fun position(qty: String) = Position("XAUUSD", BigDecimal(qty), BigDecimal("1990"))

    @Test
    fun `a fully audited fill shows its levels, currencies, positions and a red loss`() {
        val record =
            TradeRecord(
                trade = Trade("o1", "XAUUSD", BigDecimal("2000"), BigDecimal("1"), Side.SELL, 0L),
                realized = BigDecimal("-12.5"),
                strategyId = "s<1>",
                orderType = "STOP",
                riskUsd = BigDecimal("15"),
                stopLossPrice = BigDecimal("2010"),
                takeProfitPrice = BigDecimal("1980"),
                nativeRealized = BigDecimal("-11"),
                nativeCurrency = "EUR",
                accountRealized = BigDecimal("-12.5"),
                accountCurrency = "USD",
                fxRate = BigDecimal("1.13"),
                accountPositionBefore = position("1"),
                accountPositionAfter = position("0"),
                strategyPositionBefore = position("1"),
                strategyPositionAfter = position("0"),
                contractSize = BigDecimal("100"),
            )

        val html = HtmlTradesTable.render(listOf(record), HtmlReportConfig())

        assertThat(html).contains("<span class=\"side-sell\">SELL</span>")
        assertThat(html).contains("<td>s&lt;1&gt;</td>", "<td>CLOSE_LONG</td>", "<td>STOP</td>")
        assertThat(html).contains("<td>15</td><td>2010</td><td>1980</td>")
        assertThat(html).contains("<td class=\"neg\">-12.5</td>", "<td class=\"neg\">-12.5 USD</td>")
        assertThat(html).contains("<td class=\"neg\">-11 EUR</td>", "<td>1.13</td>")
        assertThat(html).contains("<td>1 @1990</td><td>0 @1990</td>")
        assertThat(html).contains("<td>100</td><td>200000</td>")
    }

    @Test
    fun `a bare fill shows placeholders, identity fx and a neutral zero`() {
        val record =
            TradeRecord(
                trade = Trade("o2", "XAUUSD", BigDecimal("2000"), BigDecimal("1"), Side.BUY, 0L),
                realized = BigDecimal.ZERO,
                strategyId = "s",
            )

        val html = HtmlTradesTable.render(listOf(record), HtmlReportConfig())

        assertThat(html).contains("<span class=\"side-buy\">BUY</span>", "<td>unknown</td>")
        assertThat(html).contains("<td>n/a</td><td>n/a</td><td>n/a</td><td>0</td><td>n/a</td><td>n/a</td>")
        assertThat(html).contains("<td>identity</td>", "<td>n/a @n/a</td>", "<td>1</td><td>2000</td>")
    }
}
