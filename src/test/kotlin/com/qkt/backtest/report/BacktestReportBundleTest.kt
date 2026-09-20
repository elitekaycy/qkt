package com.qkt.backtest.report

import com.qkt.backtest.Backtest
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.report.BacktestReportFixtures.evidence
import com.qkt.backtest.report.BacktestReportFixtures.ticks
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.evidence.EvidenceHasher
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestReportBundleTest {
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
        assertThat(dir.resolve("orders.jsonl")).exists()
        assertThat(dir.resolve("pnl_components.csv")).exists()
        assertThat(dir.resolve("manifest.json")).exists()

        val json = Files.readString(dir.resolve("result.json"))
        assertThat(json).contains("\"schema\": \"qkt-backtest-result-v1\"")
        assertThat(json).contains("\"schemaVersion\": 1")
        assertThat(json).contains("\"cadence\": \"CANDLE_CLOSE\"")
        assertThat(json).contains("\"inputSummary\": {\"attemptedFeedTicks\": 5")
        assertThat(json).contains("\"liveTicks\": 5")
        assertThat(json).contains("\"warmupTicks\": 0")
        assertThat(json).contains("\"liveCandles\": 4")
        assertThat(json).contains("\"streamCandles\": {}")
        assertThat(json).contains("\"strategyCandleEvaluations\": {}")
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
        assertThat(tradesCsv.lines().first()).contains("fillNotional,reducedExposure,legId,legAction")
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
                "orders.jsonl",
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
}
