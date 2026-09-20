package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.MonteCarloSummary
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.TradeRecord
import com.qkt.common.Side
import com.qkt.events.RiskRejectedEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.Trade
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReportPrinterMetricsTest : ReportPrinterFixture() {
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

    @Test
    fun `json report carries normalized trade summary and escapes object keys`() {
        val trades =
            listOf(
                trade("order-1", Side.BUY, realized = "10.00", riskUsd = "5.00", price = "100", quantity = "2"),
                trade("order-2", Side.SELL, realized = "-4.00", riskUsd = "7.00", price = "110", quantity = "1"),
            )
        val rejection =
            RiskRejectedEvent(
                request =
                    OrderRequest.Market(
                        id = "reject-1",
                        symbol = "XAUUSD",
                        side = Side.BUY,
                        quantity = BigDecimal.ONE,
                        timeInForce = TimeInForce.GTC,
                        timestamp = 3L,
                        strategyId = "strat",
                    ),
                reason = "max notional",
                timestamp = 3L,
            )
        val res =
            BacktestResult(
                trades = trades,
                rejections = listOf(rejection),
                finalPositions = emptyMap(),
                global = report("0").copy(tradeCount = trades.size, totalPnL = BigDecimal("6.00")),
                perStrategy = mapOf("book:\"alpha\"" to report("0")),
                cadence = SampleCadence.FILL,
            )
        val buf = ByteArrayOutputStream()

        ReportPrinter.print(res, ReportFormat.Json, PrintStream(buf), BrokerKind.PAPER)

        val root = Json.parseToJsonElement(buf.toString()).jsonObject
        val summary = root.getValue("tradeSummary").jsonObject
        assertThat(summary.getValue("fills").jsonPrimitive.content).isEqualTo("2")
        assertThat(summary.getValue("buyFills").jsonPrimitive.content).isEqualTo("1")
        assertThat(summary.getValue("sellFills").jsonPrimitive.content).isEqualTo("1")
        assertThat(summary.getValue("unknownPositionFills").jsonPrimitive.content).isEqualTo("2")
        assertThat(summary.getValue("positionAttribution").jsonPrimitive.content)
            .isEqualTo("strategy_position_transition")
        assertThat(summary.getValue("buyRealized").jsonPrimitive.content).isEqualTo("10.00000000")
        assertThat(summary.getValue("sellRealized").jsonPrimitive.content).isEqualTo("-4.00000000")
        assertThat(summary.getValue("grossProfit").jsonPrimitive.content).isEqualTo("10.00000000")
        assertThat(summary.getValue("grossLoss").jsonPrimitive.content).isEqualTo("-4.00000000")
        assertThat(summary.getValue("riskAuditedFills").jsonPrimitive.content).isEqualTo("2")
        assertThat(summary.getValue("avgRiskUsd").jsonPrimitive.content).isEqualTo("6.00000000")
        assertThat(summary.getValue("tradedNotional").jsonPrimitive.content).isEqualTo("31000.00000000")
        assertThat(summary.getValue("maxFillNotional").jsonPrimitive.content).isEqualTo("20000.00000000")
        assertThat(summary.getValue("rejectionRate").jsonPrimitive.content).isEqualTo("0.33333333")
        assertThat(root.getValue("perStrategy").jsonObject).containsKey("book:\"alpha\"")
    }

    private fun trade(
        orderId: String,
        side: Side,
        realized: String,
        riskUsd: String,
        price: String,
        quantity: String,
    ): TradeRecord =
        TradeRecord(
            trade =
                Trade(
                    orderId = orderId,
                    symbol = "XAUUSD",
                    price = BigDecimal(price),
                    quantity = BigDecimal(quantity),
                    side = side,
                    timestamp = 1L,
                ),
            realized = BigDecimal(realized),
            strategyId = "strat",
            riskUsd = BigDecimal(riskUsd),
            contractSize = BigDecimal("100"),
        )
}
