package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestCommandReportTest : BacktestCommandFixture() {
    @Test
    fun `produces parseable JSON report with --json`() {
        val (code, stdout, stderr) =
            runBacktest(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--data-root",
                "src/test/resources/cli/data",
                "--allow-incomplete",
                "--json",
            )
        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        val payload = stdout.trim().lines().last()
        val obj = Json.parseToJsonElement(payload) as JsonObject
        // The fixture strategy declares `BACKTEST:BTCUSDT` and buys on every close > 100; with the
        // broker-prefixed symbol flowing through to the feed, those ticks route to the strategy and
        // actually fill. A zero here means the symbol mismatch is back (#214).
        assertThat(obj["trades"]?.jsonPrimitive?.intOrNull).isNotNull.isGreaterThan(0)
        assertThat(obj["finalRealized"]).isNotNull
        assertThat(obj["finalUnrealized"]).isNotNull
        assertThat(obj["totalPnL"]).isNotNull
        assertThat(obj["winRate"]).isNotNull
        assertThat(obj["maxDrawdown"]).isNotNull
        assertThat(obj["maxConsecutiveLosses"]?.jsonPrimitive?.intOrNull).isNotNull
        assertThat(obj["cadence"]?.jsonPrimitive?.contentOrNull).isNotNull
    }

    @Test
    fun `backtest can write a detailed report bundle`(
        @TempDir tmp: Path,
    ) {
        val reportDir = tmp.resolve("report")
        val (code, stdout, stderr) =
            runBacktest(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--data-root",
                "src/test/resources/cli/data",
                "--allow-incomplete",
                "--report-dir",
                reportDir.toString(),
                "--json",
            )
        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        assertThat(reportDir.resolve("result.json")).exists()
        assertThat(reportDir.resolve("trades.csv")).exists()
        val tradesCsv = Files.readString(reportDir.resolve("trades.csv"))
        assertThat(tradesCsv.lines().first()).contains("accountPositionQtyBefore")
        assertThat(tradesCsv.lines().first()).contains("fillNotional")
        assertThat(tradesCsv.lines().first()).contains("positionEffect")
        assertThat(tradesCsv.lines().first()).contains("orderType")
        assertThat(tradesCsv.lines().drop(1)).anyMatch { it.contains(",OPEN_LONG,Market,") }
    }

    @Test
    fun `json report carries selected execution preset evidence`() {
        val (code, stdout, stderr) =
            runBacktest(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--data-root",
                "src/test/resources/cli/data",
                "--allow-incomplete",
                "--json",
                "--execution",
                "mt5-realistic",
                "--seed",
                "7",
                "--execution-latency",
                "fixed:100ms",
                "--stop-latency",
                "300ms",
                "--tp-fill",
                "level",
                "--slippage",
                "fixed-points:3",
            )

        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        val obj = Json.parseToJsonElement(stdout.trim().lines().last()) as JsonObject
        val execution =
            obj["evidence"]!!
                .jsonObject["execution"]!!
                .jsonObject
        assertThat(execution["preset"]?.jsonPrimitive?.contentOrNull).isEqualTo("mt5-realistic")
        assertThat(execution["broker"]?.jsonPrimitive?.contentOrNull).isEqualTo("mt5-sim")
        assertThat(execution["seed"]?.jsonPrimitive?.contentOrNull).isEqualTo("7")
        assertThat(execution["latencyModel"]?.jsonPrimitive?.contentOrNull).isEqualTo("fixed:100ms")
        assertThat(execution["stopLatencyModel"]?.jsonPrimitive?.contentOrNull).isEqualTo("fixed:300ms")
        assertThat(execution["takeProfitFillModel"]?.jsonPrimitive?.contentOrNull).isEqualTo("level")
        assertThat(execution["candleCloseModel"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("heartbeat:1000ms grace:2000ms")
        assertThat(execution["slippageModel"]?.jsonPrimitive?.contentOrNull).isEqualTo("fixed-points:3")
        assertThat(execution["venueRules"]?.jsonPrimitive?.contentOrNull).contains("tradeStopsLevel")
    }
}
