package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestCommandPinnedDatasetTest : BacktestCommandFixture() {
    private fun snapshot(
        root: Path,
        rows: List<String>,
    ): Path {
        val dataRoot = root.resolve("data")
        val dir = dataRoot.resolve("symbols").resolve("XAUUSD")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("2024-01-01.csv"),
            (
                listOf("timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume") + rows
            ).joinToString("\n") + "\n",
        )
        val out = root.resolve("xau-snapshot.json")
        val code =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "snapshot",
                        "XAUUSD",
                        "--from",
                        "2024-01-01",
                        "--to",
                        "2024-01-02",
                        "--data-root",
                        dataRoot.toString(),
                        "--out",
                        out.toString(),
                    ),
                ),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        return out
    }

    @Test
    fun `backtest with dataset snapshot emits pinned dataset evidence`(
        @TempDir tmp: Path,
    ) {
        val snapshot = tmp.resolve("btc-snapshot.json")
        val snapshotCode =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "snapshot",
                        "BTCUSDT",
                        "--from",
                        "2024-01-15",
                        "--to",
                        "2024-01-16",
                        "--data-root",
                        "src/test/resources/cli/data",
                        "--out",
                        snapshot.toString(),
                    ),
                ),
            ).run()
        assertThat(snapshotCode).isEqualTo(ExitCodes.SUCCESS)

        val (code, stdout, stderr) =
            runBacktest(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--dataset",
                snapshot.toString(),
                "--allow-incomplete",
                "--json",
            )

        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        val obj = Json.parseToJsonElement(stdout.trim().lines().last()) as JsonObject
        val evidence = obj["evidence"]!!.jsonObject
        val dataset = evidence["dataset"]!!.jsonObject
        assertThat(dataset["id"]?.jsonPrimitive?.contentOrNull).startsWith("qkt-ds-btcusdt-2024-01-15_2024-01-16-")
        assertThat(dataset["hash"]?.jsonPrimitive?.contentOrNull).startsWith("sha256:")
        assertThat(dataset["mutableStore"]?.jsonPrimitive?.contentOrNull).isEqualTo("false")
    }

    @Test
    fun `pinned backtest fails when strategy reads quote fields without bid ask data`(
        @TempDir tmp: Path,
    ) {
        val snapshot =
            snapshot(
                tmp,
                listOf("1704067200000,XAUUSD,2000.00000000,1.00000000,,,,"),
            )
        val strategy = writeStrategy(tmp, "gold.spread > 0")

        val (code, _, stderr) =
            runBacktest(
                "backtest",
                strategy.toString(),
                "--from",
                "2024-01-01",
                "--to",
                "2024-01-02",
                "--dataset",
                snapshot.toString(),
            )

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr)
            .contains("dataset field capability check failed")
            .contains("bid/ask/spread")
            .contains("gold")
    }

    @Test
    fun `pinned backtest fails when strategy reads volume without volume data`(
        @TempDir tmp: Path,
    ) {
        val snapshot =
            snapshot(
                tmp,
                listOf("1704067200000,XAUUSD,2000.00000000,,1999.90000000,2000.10000000,,"),
            )
        val strategy = writeStrategy(tmp, "gold.volume > 0")

        val (code, _, stderr) =
            runBacktest(
                "backtest",
                strategy.toString(),
                "--from",
                "2024-01-01",
                "--to",
                "2024-01-02",
                "--dataset",
                snapshot.toString(),
            )

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr)
            .contains("dataset field capability check failed")
            .contains("volume")
            .contains("gold")
    }
}
