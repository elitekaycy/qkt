package com.qkt.cli

import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.LocalBarStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
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

    private fun writeStrategy(
        dir: Path,
        condition: String,
    ): Path {
        val strategy = dir.resolve("strategy.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN $condition
                THEN LOG "gate"
            """.trimIndent(),
        )
        return strategy
    }

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
                "--json",
                "--execution",
                "mt5-realistic",
                "--seed",
                "7",
                "--execution-latency",
                "fixed:100ms",
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
        assertThat(execution["slippageModel"]?.jsonPrimitive?.contentOrNull).isEqualTo("fixed-points:3")
        assertThat(execution["venueRules"]?.jsonPrimitive?.contentOrNull).contains("tradeStopsLevel")
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
