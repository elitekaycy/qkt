package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquitySample
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.ReplayCausalityReport
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.report.BacktestReportFixtures.tradeRecord
import com.qkt.common.Side
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestResultJsonAuditTest {
    @Test
    fun `result json carries audited trade risk drawdown and artifact facts`(
        @TempDir dir: Path,
    ) {
        val trades =
            listOf(
                tradeRecord(
                    orderId = "order,1",
                    timestamp = 60_000L,
                    side = Side.BUY,
                    realized = "25.00",
                    riskUsd = "10.00",
                    price = "100.00",
                    quantity = "2",
                    contractSize = "100",
                    fxSource = "test,fx",
                ),
                tradeRecord(
                    orderId = "order-2",
                    timestamp = 120_000L,
                    side = Side.SELL,
                    realized = "-5.00",
                    riskUsd = "20.00",
                    price = "110.00",
                    quantity = "1",
                    contractSize = "100",
                ),
            )
        val report =
            PerformanceReport(
                realizedTotal = BigDecimal("20.00"),
                unrealizedTotal = BigDecimal.ZERO,
                totalPnL = BigDecimal("20.00"),
                tradeCount = trades.size,
                winRate = BigDecimal("0.50000000"),
                maxDrawdown = BigDecimal("0.05000000"),
                profitFactor = BigDecimal("5.00000000"),
                avgWin = BigDecimal("25.00000000"),
                avgLoss = BigDecimal("-5.00000000"),
                largestWin = BigDecimal("25.00000000"),
                largestLoss = BigDecimal("-5.00000000"),
                maxConsecutiveLosses = 1,
                sharpeRatio = BigDecimal("1.25"),
                calmarRatio = BigDecimal("0.40"),
                equityCurve =
                    listOf(
                        EquitySample(0L, BigDecimal("10000")),
                        EquitySample(120_000L, BigDecimal("10020")),
                    ),
                drawdownPeriods =
                    listOf(
                        DrawdownPeriod(
                            peakTimestamp = 0L,
                            peakEquity = BigDecimal("10000"),
                            troughTimestamp = 60_000L,
                            troughEquity = BigDecimal("9500"),
                            recoveryTimestamp = 120_000L,
                            depthPct = BigDecimal("0.05"),
                            durationMs = 120_000L,
                            ongoing = false,
                        ),
                    ),
                dailyPnL = mapOf(java.time.LocalDate.of(1970, 1, 1) to BigDecimal("20.00")),
                maxDailyDrawdown = BigDecimal("0.02000000"),
                turnover = BigDecimal("3.10000000"),
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
                        timestamp = 130_000L,
                        strategyId = "s1",
                    ),
                reason = "max notional, blocked",
                timestamp = 130_000L,
                sequenceId = 8L,
            )
        val approvedRequest =
            OrderRequest.Market(
                id = "approved-1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                timeInForce = TimeInForce.GTC,
                timestamp = 120_000L,
                strategyId = "s1",
            )
        val result =
            BacktestResult(
                trades = trades,
                rejections = listOf(rejection),
                finalPositions = emptyMap(),
                global = report,
                perStrategy = mapOf("s1" to report),
                cadence = SampleCadence.FILL,
                causality =
                    ReplayCausalityReport(
                        approvedOrders =
                            listOf(
                                OrderEvent(
                                    request = approvedRequest,
                                    timestamp = 120_000L,
                                    sequenceId = 7L,
                                ),
                            ),
                        ruleDecisions = emptyList(),
                        decisionOrderLinks = emptyList(),
                        accountedFills = emptyList(),
                    ),
            )

        BacktestReportWriter(dir).write(result)

        val root = Json.parseToJsonElement(Files.readString(dir.resolve("result.json"))).jsonObject
        assertThat(root.getValue("schema").jsonPrimitive.content).isEqualTo("qkt-backtest-result-v1")
        assertThat(root.getValue("schemaVersion").jsonPrimitive.content).isEqualTo("1")
        val summary = root.getValue("tradeSummary").jsonObject
        assertThat(summary.getValue("fills").jsonPrimitive.content).isEqualTo("2")
        assertThat(summary.getValue("buyFills").jsonPrimitive.content).isEqualTo("1")
        assertThat(summary.getValue("sellFills").jsonPrimitive.content).isEqualTo("1")
        assertThat(summary.getValue("unknownPositionFills").jsonPrimitive.content).isEqualTo("2")
        assertThat(summary.getValue("positionAttribution").jsonPrimitive.content)
            .isEqualTo("strategy_position_transition")
        assertThat(summary.getValue("buyRealized").jsonPrimitive.content).isEqualTo("25.00000000")
        assertThat(summary.getValue("sellRealized").jsonPrimitive.content).isEqualTo("-5.00000000")
        assertThat(summary.getValue("grossProfit").jsonPrimitive.content).isEqualTo("25.00000000")
        assertThat(summary.getValue("grossLoss").jsonPrimitive.content).isEqualTo("-5.00000000")
        assertThat(summary.getValue("riskAuditedFills").jsonPrimitive.content).isEqualTo("2")
        assertThat(summary.getValue("minRiskUsd").jsonPrimitive.content).isEqualTo("10.00")
        assertThat(summary.getValue("avgRiskUsd").jsonPrimitive.content).isEqualTo("15.00000000")
        assertThat(summary.getValue("maxRiskUsd").jsonPrimitive.content).isEqualTo("20.00")
        assertThat(summary.getValue("tradedNotional").jsonPrimitive.content).isEqualTo("31000.00000000")
        assertThat(summary.getValue("maxFillNotional").jsonPrimitive.content).isEqualTo("20000.00000000")
        assertThat(summary.getValue("rejections").jsonPrimitive.content).isEqualTo("1")
        assertThat(summary.getValue("rejectionRate").jsonPrimitive.content).isEqualTo("0.33333333")

        val global = root.getValue("global").jsonObject
        assertThat(global.getValue("maxDailyDrawdown").jsonPrimitive.content).isEqualTo("0.02000000")
        val dailyPnl =
            global
                .getValue("dailyPnL")
                .jsonObject
                .getValue("1970-01-01")
                .jsonPrimitive
                .content
        assertThat(dailyPnl).isEqualTo("20.00")
        val drawdown = global.getValue("drawdownPeriods").toString()
        assertThat(drawdown).contains(""""peakTimestamp":0""")
        assertThat(drawdown).contains(""""depthPct":"0.05"""")

        val artifacts = root.getValue("artifacts").jsonObject
        assertThat(artifacts.getValue("tradesCsv").jsonPrimitive.content).isEqualTo("trades.csv")
        assertThat(artifacts.getValue("pnlComponentsCsv").jsonPrimitive.content).isEqualTo("pnl_components.csv")
        val strategyEquityCsv =
            artifacts
                .getValue("equityStrategyCsv")
                .jsonObject
                .getValue("s1")
                .jsonPrimitive
                .content
        assertThat(strategyEquityCsv).isEqualTo("equity_s1.csv")

        val tradesCsv = Files.readString(dir.resolve("trades.csv"))
        assertThat(tradesCsv).contains("\"order,1\"")
        assertThat(tradesCsv).contains("\"test,fx\"")
        assertThat(tradesCsv).contains(",25.00,25.00,25.00,25.00,")
        assertThat(tradesCsv).contains(",20000.00,true,,\n")
        val pnlComponentsCsv = Files.readString(dir.resolve("pnl_components.csv"))
        assertThat(pnlComponentsCsv.lines().first())
            .isEqualTo("scope,strategy,date,tradeRealized,adjustment,dailyPnL")
        assertThat(pnlComponentsCsv).contains("global,,1970-01-01,20.00,0.00,20.00")
        assertThat(pnlComponentsCsv).contains("strategy,s1,1970-01-01,20.00,0.00,20.00")
        assertThat(Files.readString(dir.resolve("rejections.csv"))).contains("\"max notional, blocked\"")
        val orders = Files.readAllLines(dir.resolve("orders.jsonl"))
        assertThat(orders).hasSize(2)
        assertThat(orders[0])
            .contains("\"decision\":\"approved\"")
            .contains("\"orderId\":\"approved-1\"")
            .contains("\"orderType\":\"Market\"")
        assertThat(orders[1])
            .contains("\"decision\":\"rejected\"")
            .contains("\"reason\":\"max notional, blocked\"")
            .contains("\"orderId\":\"reject-1\"")
    }
}
