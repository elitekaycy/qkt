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

class BacktestCommissionFlagTest : BacktestCommandFixture() {
    @Test
    fun `commission-per-lot rebooks every fill at the override rate`(
        @TempDir home: Path,
        @TempDir data: Path,
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
                commissionPerLot: 3.50
            """.trimIndent(),
        )
        fun paid(vararg extra: String): java.math.BigDecimal {
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
            return obj["global"]!!.jsonObject["commissionPaid"]?.jsonPrimitive?.content?.toBigDecimal()
                ?: java.math.BigDecimal.ZERO
        }
        // Same fills both runs (deterministic replay): the override books exactly 2x the file rate.
        assertThat(paid("--commission-per-lot", "7")).isEqualByComparingTo(paid().multiply(java.math.BigDecimal("2")))
        assertThat(paid()).isGreaterThan(java.math.BigDecimal.ZERO)
    }
}
