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

class BacktestOrderSpacingOptionTest : BacktestCommandFixture() {
    private fun latencyModel(vararg extra: String): String? {
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
                "--broker",
                "mt5-sim",
                *extra,
            )
        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        val obj = Json.parseToJsonElement(stdout.trim().lines().last()) as JsonObject
        return obj["evidence"]!!
            .jsonObject["execution"]!!
            .jsonObject["latencyModel"]
            ?.jsonPrimitive
            ?.contentOrNull
    }

    @Test
    fun `no order spacing leaves the latency evidence unchanged`() {
        assertThat(latencyModel()).isEqualTo("zero")
    }

    @Test
    fun `the order spacing flag is recorded next to the latency`() {
        assertThat(latencyModel("--execution-latency", "100ms", "--order-spacing", "150ms"))
            .isEqualTo("fixed:100ms lane:150ms")
    }

    @Test
    fun `the config order_spacing key applies when the flag is absent`(
        @TempDir tmp: Path,
    ) {
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(config, "execution:\n  order_spacing: 150ms\n")

        assertThat(latencyModel("--config", config.toString())).isEqualTo("zero lane:150ms")
    }
}
