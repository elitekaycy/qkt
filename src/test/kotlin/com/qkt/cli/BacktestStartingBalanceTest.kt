package com.qkt.cli

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestStartingBalanceTest : BacktestCommandFixture() {
    @Test
    fun `the flag wins over the config balance`() {
        val resolved = resolveBacktestStartingBalance("2500", BigDecimal("50000"))

        assertThat(resolved.amount).isEqualByComparingTo("2500")
        assertThat(resolved.source).isEqualTo(StartingBalanceSource.FLAG)
    }

    @Test
    fun `a positive config balance is used when the flag is absent`() {
        val resolved = resolveBacktestStartingBalance(null, BigDecimal("50000"))

        assertThat(resolved.amount).isEqualByComparingTo("50000")
        assertThat(resolved.source).isEqualTo(StartingBalanceSource.CONFIG)
    }

    @Test
    fun `an unset config balance falls back to the backtest default`() {
        val resolved = resolveBacktestStartingBalance(null, BigDecimal.ZERO)

        assertThat(resolved.amount).isEqualByComparingTo("10000")
        assertThat(resolved.source).isEqualTo(StartingBalanceSource.DEFAULT)
    }

    @Test
    fun `a backtest with a config starts from the config balance the daemon would use`(
        @TempDir tmp: Path,
    ) {
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(config, "starting_balance: 50000\n")

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
                "--config",
                config.toString(),
            )

        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        assertThat(stderr).contains("starting balance 50000 from config starting_balance")
        val obj = Json.parseToJsonElement(stdout.trim().lines().last()) as JsonObject
        val firstEquity =
            (obj["global"]!!.jsonObject["equityCurve"] as JsonArray)
                .first()
                .jsonObject["equity"]!!
                .jsonPrimitive.content
        assertThat(BigDecimal(firstEquity)).isEqualByComparingTo("50000")
    }
}
