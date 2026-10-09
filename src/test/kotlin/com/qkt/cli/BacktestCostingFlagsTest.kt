package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestCostingFlagsTest : BacktestCommandFixture() {
    private fun instrumentsYaml(
        data: Path,
        commission: String,
    ) {
        Files.writeString(
            data.resolve("instruments.yaml"),
            """
            instruments:
              - qktSymbol: BACKTEST:BTCUSDT
                contractSize: 1
                volumeStep: 0.01
                volumeMin: 0.01
                pointSize: 0.01
                digits: 2
                tradeStopsLevelPoints: 0
                commissionPerLot: $commission
                swapLongPoints: -1.20
                swapShortPoints: 0.80
            """.trimIndent(),
        )
    }

    private fun paid(
        home: Path,
        data: Path,
        field: String,
        vararg extra: String,
    ): java.math.BigDecimal {
        val (code, stdout, stderr) =
            runBacktestWithRunsRoot(
                home,
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--data-root",
                "src/test/resources/cli/data",
                "--instruments",
                data.resolve("instruments.yaml").toString(),
                "--allow-incomplete",
                "--json",
                *extra,
            )
        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        val obj = Json.parseToJsonElement(stdout.trim().lines().last()) as JsonObject
        return obj["global"]!!.jsonObject[field]?.jsonPrimitive?.content?.toBigDecimal()
            ?: java.math.BigDecimal.ZERO
    }

    @Test
    fun `commission-per-lot rebooks every fill at the override rate`(
        @TempDir home: Path,
        @TempDir data: Path,
    ) {
        instrumentsYaml(data, "3.50")
        // Same fills both runs (deterministic replay): the override books exactly 2x the file rate.
        val file = paid(home, data, "commissionPaid")
        assertThat(file).isGreaterThan(java.math.BigDecimal.ZERO)
        assertThat(paid(home, data, "commissionPaid", "--commission-per-lot", "7"))
            .isEqualByComparingTo(file.multiply(java.math.BigDecimal("2")))
    }

    @Test
    fun `swap-scale multiplies overnight financing`(
        @TempDir home: Path,
        @TempDir data: Path,
    ) {
        instrumentsYaml(data, "0")
        // The fixture holds its position through the 21:00 UTC rollover; scaling by 3 triples it.
        val base = paid(home, data, "swapPaid")
        assertThat(base.abs()).isGreaterThan(java.math.BigDecimal.ZERO)
        assertThat(paid(home, data, "swapPaid", "--swap-scale", "3"))
            .isEqualByComparingTo(base.multiply(java.math.BigDecimal("3")))
    }
}
