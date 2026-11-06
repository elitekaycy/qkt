package com.qkt.backtest.report

import com.qkt.accounting.AccountingSnapshot
import com.qkt.accounting.FxConversion
import com.qkt.backtest.PerformanceReport
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccountingTablesTest {
    private val zero = BigDecimal.ZERO
    private val report =
        PerformanceReport(
            realizedTotal = zero,
            unrealizedTotal = zero,
            totalPnL = zero,
            tradeCount = 0,
            winRate = zero,
            maxDrawdown = zero,
            profitFactor = null,
            avgWin = zero,
            avgLoss = zero,
            largestWin = zero,
            largestLoss = zero,
            maxConsecutiveLosses = 0,
            sharpeRatio = null,
            calmarRatio = null,
            equityCurve = emptyList(),
        )
    private val plain = AccountingSnapshot("USD", "fail", "identity", emptyMap(), emptyList(), emptyList())
    private val busy =
        plain.copy(
            configuredSymbols = mapOf("EUR" to "EURUSD", "AUD" to "AUDUSD"),
            conversions = listOf(FxConversion("EUR", "USD", BigDecimal("1.10"), 5L, "store")),
            warnings = listOf("gap", "late"),
        )

    @Test
    fun `the html table lists configured symbols, conversions and warnings when present`() {
        val html = HtmlAccountingTable.render(busy, report)

        assertThat(html).contains("<td>configured FX symbols</td>")
        assertThat(html).contains("EUR-&gt;USD rate=1.10 timestamp=5 source=store")
        assertThat(html).contains("<td>warnings</td><td>gap; late</td>")
        assertThat(html).contains("<td>costs incurred</td><td>none</td>")
    }

    @Test
    fun `a plain snapshot renders no optional rows`() {
        val html = HtmlAccountingTable.render(plain, report.copy(dividendsPaid = BigDecimal("2.5")))

        assertThat(html).doesNotContain("configured FX symbols", "observed conversions", "<td>warnings</td>")
        assertThat(html).contains("<td>dividends</td>").doesNotContain("costs incurred")
    }

    @Test
    fun `the json section sorts configured symbols and lists conversions`() {
        val json = AccountingJson.render(busy)

        assertThat(json).contains("\"configuredSymbols\": {\"AUD\": \"AUDUSD\",\"EUR\": \"EURUSD\"}")
        assertThat(json).contains("{\"from\": \"EUR\", \"to\": \"USD\", \"rate\": \"1.10\", \"timestamp\": 5")
        assertThat(json).contains("\"warnings\": [\"gap\",\"late\"]")
        assertThat(AccountingJson.render(null)).isEqualTo("null")
    }

    @Test
    fun `financing csv adds funding and dividend rows only when paid`() {
        assertThat(FinancingCsv.render(BigDecimal("3"))).isEqualTo("component,paid,netPnlImpact\nswap,3,-3\n")
        assertThat(FinancingCsv.render(BigDecimal("3"), BigDecimal("1.5"), BigDecimal("-2")).lines())
            .contains("funding,1.5,-1.5", "dividends,-2,2")
    }
}
