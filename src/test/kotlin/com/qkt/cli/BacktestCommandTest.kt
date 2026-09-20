package com.qkt.cli

import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.LocalBarStore
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestCommandTest : BacktestCommandFixture() {
    @Test
    fun `an unknown position mode is a setup error naming the valid values`(
        @TempDir dir: Path,
    ) {
        val strategy = writeStrategy(dir, "true")
        val (code, _, err) =
            runBacktest(
                "backtest",
                strategy.toString(),
                "--from",
                "2024-01-01",
                "--to",
                "2024-01-02",
                "--data-root",
                dir.toString(),
                "--position-mode",
                "both-at-once",
            )
        assertThat(code).isNotZero()
        assertThat(err).contains("unknown position mode").contains("netting, hedging")
    }

    @Test
    fun `produces text report from fixture data`() {
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
            )
        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("Trades:")
        assertThat(stdout).contains("Final realized:")
        assertThat(stdout).contains("Max drawdown:")
    }

    @Test
    fun `missing required from flag throws ArgError`() {
        assertThatThrownBy {
            runBacktest(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--to",
                "2024-01-16",
                "--data-root",
                "src/test/resources/cli/data",
            )
        }.isInstanceOf(ArgError::class.java)
            .hasMessageContaining("--from")
    }

    @Test
    fun `missing strategy file exits with user error`() {
        val (code, _, stderr) =
            runBacktest(
                "backtest",
                "does_not_exist.qkt",
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
            )
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("file not found")
    }

    @Test
    fun `--data-root makes the backtest read fetched bars from that root`(
        @TempDir dataRoot: Path,
    ) {
        // Seed bars only under the custom root; the default ~/.qkt/data has nothing for this symbol.
        // Before the fix the bar store ignored --data-root and found no bars -> zero trades.
        val day = LocalDate.parse("2024-01-15")
        val day15 = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()
        val bars =
            (0 until 5).map { i ->
                val start = day15 + i * 60_000L
                Candle(
                    "BACKTEST:BTCUSDT",
                    BigDecimal("42000"),
                    BigDecimal("42010"),
                    BigDecimal("41990"),
                    BigDecimal("42005"),
                    BigDecimal("1"),
                    start,
                    start + 60_000L,
                )
            }
        LocalBarStore(root = dataRoot).writeDay("BACKTEST", "BTCUSDT", "1m", day, bars)

        val (code, stdout, stderr) =
            runBacktest(
                "backtest",
                "src/test/resources/cli/valid_strategy.qkt",
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--data-root",
                dataRoot.toString(),
                "--allow-incomplete",
                "--json",
            )

        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        val obj = Json.parseToJsonElement(stdout.trim().lines().last()) as JsonObject
        assertThat(obj["trades"]?.jsonPrimitive?.intOrNull).isNotNull.isGreaterThan(0)
    }

    @Test
    fun `backtest applies configured pre-trade notional cap`(
        @TempDir tmp: Path,
    ) {
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            """
            risk:
              max_order_notional: "1"
            """.trimIndent(),
        )
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
                "--json",
                "--config",
                config.toString(),
                "--report-dir",
                reportDir.toString(),
            )

        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        val obj = Json.parseToJsonElement(stdout.trim().lines().last()) as JsonObject
        assertThat(obj["trades"]?.jsonPrimitive?.intOrNull).isEqualTo(0)
        assertThat(Files.readString(reportDir.resolve("rejections.csv")))
            .contains("exceeds cap 1")
            .contains("BACKTEST:BTCUSDT")
    }
}
