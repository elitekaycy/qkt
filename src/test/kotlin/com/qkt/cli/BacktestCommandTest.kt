package com.qkt.cli

import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.LocalBarStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestCommandTest {
    private fun runBacktest(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = BacktestCommand(Args(argv as Array<String>)).run()
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
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
                "--json",
            )

        assertThat(code).withFailMessage("stderr=$stderr stdout=$stdout").isEqualTo(ExitCodes.SUCCESS)
        val obj = Json.parseToJsonElement(stdout.trim().lines().last()) as JsonObject
        assertThat(obj["trades"]?.jsonPrimitive?.intOrNull).isNotNull.isGreaterThan(0)
    }
}
