package com.qkt.cli

import com.qkt.backtest.BrokerKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReportPrinterDisclosureTest : ReportPrinterFixture() {
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
        val root = Json.parseToJsonElement(json).jsonObject
        val global = root.getValue("global").jsonObject

        assertThat(root.getValue("schema").jsonPrimitive.content).isEqualTo("qkt-backtest-result-v1")
        assertThat(root.getValue("schemaVersion").jsonPrimitive.content).isEqualTo("1")
        assertThat(global.getValue("commissionPaid").jsonPrimitive.content).isEqualTo("5.00")
        assertThat(global.getValue("tradeCount").jsonPrimitive.content).isEqualTo("50")
        assertThat(global.getValue("equityCurve").jsonArray).hasSize(1)
        assertThat(json).contains("\"commissionPaid\":5.00")
        assertThat(json).contains("\"executionModel\":\"paper\"")
        assertThat(json).contains("\"evidence\":null")
    }

    @Test
    fun `text and json reports disclose swap charge sign`() {
        val text = render(ReportFormat.Text, BrokerKind.PAPER, swapPaid = "2.50")
        assertThat(text).contains("Final realized:   100   (net of commission and swap)")
        assertThat(text).contains("Swap paid:        2.50")
        assertThat(text).contains("2.50 charged")

        val json = render(ReportFormat.Json, BrokerKind.PAPER, swapPaid = "-1.25")
        assertThat(json).contains("\"swapPaid\":-1.25")
    }
}
