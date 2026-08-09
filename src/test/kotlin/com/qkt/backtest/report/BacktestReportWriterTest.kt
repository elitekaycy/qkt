package com.qkt.backtest.report

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquitySample
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.TradeRecord
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.RiskRejectedEvent
import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceEnvelope
import com.qkt.evidence.EvidenceHasher
import com.qkt.evidence.ExecutionEvidence
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestReportWriterTest {
    private fun ticks(): List<Tick> = (1..5).map { i -> Tick("X", Money.of((100 + i).toString()), i * 60_000L) }

    @Test
    fun `writer produces result_json equity_csv trades_csv rejections_csv`(
        @TempDir dir: Path,
    ) {
        val noopStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }
        val backtest =
            Backtest(
                strategies = listOf("s1" to noopStrategy),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
            )
        val baseResult = backtest.run()
        val result =
            baseResult.copy(
                global =
                    baseResult.global.copy(
                        realizedTotal = Money.of("-2.5"),
                        totalPnL = Money.of("-2.5"),
                        swapPaid = Money.of("2.5"),
                    ),
                evidence = evidence(),
            )

        BacktestReportWriter(dir).write(result)

        assertThat(dir.resolve("result.json")).exists()
        assertThat(dir.resolve("equity_global.csv")).exists()
        assertThat(dir.resolve("equity_s1.csv")).exists()
        assertThat(dir.resolve("trades.csv")).exists()
        assertThat(dir.resolve("financing.csv")).exists()
        assertThat(dir.resolve("rejections.csv")).exists()
        assertThat(dir.resolve("pnl_components.csv")).exists()
        assertThat(dir.resolve("manifest.json")).exists()

        val json = Files.readString(dir.resolve("result.json"))
        assertThat(json).contains("\"schema\": \"qkt-backtest-result-v1\"")
        assertThat(json).contains("\"schemaVersion\": 1")
        assertThat(json).contains("\"cadence\": \"CANDLE_CLOSE\"")
        assertThat(json).contains("\"evidence\": {\"qktVersion\":\"test\"")
        assertThat(json).contains("\"strategyHash\":\"sha256:strategy\"")
        assertThat(json).contains("\"mutableStore\":true")
        assertThat(json).contains("\"accounting\": {\"accountCurrency\": \"USD\"")
        assertThat(json).contains("\"global\":")
        assertThat(json).contains("\"swapPaid\": \"2.50000000\"")
        assertThat(json).contains("\"perStrategy\":")
        assertThat(json).contains("\"runawayBreaker\": {\"enforceLiveBreakers\": false")
        Json.parseToJsonElement(json)

        val eqCsv = Files.readString(dir.resolve("equity_global.csv"))
        assertThat(eqCsv.lines().first()).isEqualTo("timestamp,equity")
        val tradesCsv = Files.readString(dir.resolve("trades.csv"))
        assertThat(tradesCsv.lines().first())
            .contains("realized,netAccountRealized,grossAccountRealized,nativeRealized")
        assertThat(tradesCsv.lines().first())
            .contains("nativeCurrency,accountRealized,accountCurrency,fxRate")
        assertThat(tradesCsv.lines().first()).contains("riskUsd,brokerOrderId,stopLossPrice,takeProfitPrice")
        assertThat(tradesCsv.lines().first()).contains("fillNotional,reducedExposure")
        val financingCsv = Files.readString(dir.resolve("financing.csv"))
        assertThat(financingCsv).isEqualTo("component,paid,netPnlImpact\nswap,2.50000000,-2.50000000\n")
    }

    @Test
    fun `manifest hashes every report artifact except itself`(
        @TempDir dir: Path,
    ) {
        val noopStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }
        val result =
            Backtest(
                strategies = listOf("s1" to noopStrategy),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
            ).run().copy(evidence = evidence())

        BacktestReportWriter(dir).write(result)

        val manifest = Json.parseToJsonElement(Files.readString(dir.resolve("manifest.json"))).jsonObject
        assertThat(manifest.getValue("schema").jsonPrimitive.content).isEqualTo("qkt-report-bundle-v1")
        assertThat(manifest.getValue("schemaVersion").jsonPrimitive.content).isEqualTo("1")
        assertThat(manifest.getValue("selfHashIncluded").jsonPrimitive.content).isEqualTo("false")
        assertThat(manifest.getValue("generatedAt").jsonPrimitive.content).isEqualTo("2026-06-25T00:00:00Z")

        val artifacts =
            manifest
                .getValue("artifacts")
                .jsonArray
                .associate { artifact ->
                    val obj = artifact.jsonObject
                    obj.getValue("path").jsonPrimitive.content to obj
                }
        assertThat(artifacts.keys)
            .containsExactly(
                "result.json",
                "equity_global.csv",
                "equity_s1.csv",
                "trades.csv",
                "financing.csv",
                "rejections.csv",
                "pnl_components.csv",
                "report.html",
            )
        assertThat(artifacts).doesNotContainKey("manifest.json")
        for ((path, artifact) in artifacts) {
            val file = dir.resolve(path)
            assertThat(artifact.getValue("sha256").jsonPrimitive.content).isEqualTo(EvidenceHasher.sha256(file))
            assertThat(artifact.getValue("bytes").jsonPrimitive.content).isEqualTo(Files.size(file).toString())
        }

        val resultJson = Json.parseToJsonElement(Files.readString(dir.resolve("result.json"))).jsonObject
        val manifestArtifact =
            resultJson
                .getValue("artifacts")
                .jsonObject
                .getValue("manifestJson")
                .jsonPrimitive
                .content
        assertThat(manifestArtifact).isEqualTo("manifest.json")
    }

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
            )
        val result =
            BacktestResult(
                trades = trades,
                rejections = listOf(rejection),
                finalPositions = emptyMap(),
                global = report,
                perStrategy = mapOf("s1" to report),
                cadence = SampleCadence.FILL,
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
        assertThat(tradesCsv).contains(",20000.00,true\n")
        val pnlComponentsCsv = Files.readString(dir.resolve("pnl_components.csv"))
        assertThat(pnlComponentsCsv.lines().first())
            .isEqualTo("scope,strategy,date,tradeRealized,adjustment,dailyPnL")
        assertThat(pnlComponentsCsv).contains("global,,1970-01-01,20.00,0.00,20.00")
        assertThat(pnlComponentsCsv).contains("strategy,s1,1970-01-01,20.00,0.00,20.00")
        assertThat(Files.readString(dir.resolve("rejections.csv"))).contains("\"max notional, blocked\"")
    }

    @Test
    fun `unsafe strategyId rejected before any file written`(
        @TempDir dir: Path,
    ) {
        val noopStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }
        val backtest =
            Backtest(
                strategies = listOf("../danger" to noopStrategy),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
            )
        val result = backtest.run()

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            BacktestReportWriter(dir).write(result)
        }
        assertThat(Files.list(dir).count()).isEqualTo(0L)
    }

    @Test
    fun `portfolio strategy ids are encoded in equity filenames`(
        @TempDir dir: Path,
    ) {
        val noopStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }
        val result =
            Backtest(
                strategies = listOf("book:child" to noopStrategy),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        BacktestReportWriter(dir).write(result)

        assertThat(dir.resolve("equity_book%3Achild.csv")).exists()
        assertThat(Files.readString(dir.resolve("result.json"))).contains("\"book:child\"")
    }

    private fun evidence(): EvidenceEnvelope =
        EvidenceEnvelope(
            qktVersion = "test",
            gitSha = "abc123",
            buildTimestamp = "2026-06-25T00:00:00Z",
            command = listOf("backtest", "s.qkt"),
            strategyHash = "sha256:strategy",
            dataset = DatasetEvidence(mutableStore = true),
            execution = ExecutionEvidence(preset = "paper-fast", broker = "paper"),
        )

    private fun tradeRecord(
        orderId: String,
        timestamp: Long,
        side: Side,
        realized: String,
        riskUsd: String,
        price: String,
        quantity: String,
        contractSize: String,
        fxSource: String = "test",
    ): TradeRecord =
        TradeRecord(
            trade =
                Trade(
                    orderId = orderId,
                    symbol = "XAUUSD",
                    price = BigDecimal(price),
                    quantity = BigDecimal(quantity),
                    side = side,
                    timestamp = timestamp,
                ),
            realized = BigDecimal(realized),
            strategyId = "s1",
            riskUsd = BigDecimal(riskUsd),
            nativeRealized = BigDecimal(realized),
            nativeCurrency = "USD",
            accountRealized = BigDecimal(realized),
            accountCurrency = "USD",
            fxRate = BigDecimal.ONE,
            fxRateTimestamp = timestamp,
            fxSource = fxSource,
            contractSize = BigDecimal(contractSize),
        )
}
