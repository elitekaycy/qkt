package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Tick
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The acceptance gate for tick-resolved fills (`--bars --tick-fills`): bars drive signals but fills
 * resolve on real ticks, so the result must be byte-identical to a full-tick replay. A volatile
 * intrabar path makes SL/TP both-hit and entry-trigger cases actually occur, which is exactly where
 * plain `--bars` drifts and tick-resolved must not.
 */
class TickResolvedParityTest {
    // 1 tick/min, a fast high-amplitude sine so a 15m bar (15 ticks) swings far enough to hit a 1%
    // stop / 2R target intrabar — the case that separates tick-resolved from plain --bars.
    private fun ticksFor(days: Int): List<Tick> {
        val start =
            LocalDate
                .parse("2024-01-02")
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        return (0 until days * 1440).map { m ->
            val mid = 1850.0 + 30.0 * sin(m / 7.0)
            Tick("XAUUSD", Money.of("%.3f".format(mid)), start + m * 60_000L)
        }
    }

    private fun seedTicks(
        dataRoot: Path,
        days: Int,
    ) {
        ticksFor(days)
            .groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.timestamp), ZoneOffset.UTC) }
            .forEach { (day, dayTicks) ->
                val f = dataRoot.resolve("symbols").resolve("XAUUSD").resolve("$day.bin")
                Files.createDirectories(f.parent)
                BinaryTickWriter().write(f, "XAUUSD", dayTicks)
            }
    }

    private fun strategyFile(dir: Path): Path {
        val s = dir.resolve("s.qkt")
        Files.writeString(
            s,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 15m
            RULES
                WHEN ema(gold.close, 3) CROSSES ABOVE ema(gold.close, 9)
                THEN BUY gold SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
                WHEN ema(gold.close, 3) CROSSES BELOW ema(gold.close, 9)
                THEN CLOSE gold
            """.trimIndent(),
        )
        return s
    }

    private fun runJson(
        dir: Path,
        dataRoot: Path,
        from: String,
        to: String,
        extra: List<String>,
    ): String {
        val out = ByteArrayOutputStream()
        val orig = System.out
        val code =
            try {
                System.setOut(PrintStream(out))
                BacktestCommand(
                    Args(
                        (
                            listOf(
                                "backtest",
                                strategyFile(dir).toString(),
                                "--from",
                                from,
                                "--to",
                                to,
                                "--data-root",
                                dataRoot.toString(),
                                "--no-fetch",
                                "--allow-incomplete",
                                "--json",
                            ) + extra
                        ).toTypedArray(),
                    ),
                ).run()
            } finally {
                System.setOut(orig)
            }
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        return out.toString()
    }

    private fun field(
        json: String,
        key: String,
    ): String = Regex("\"$key\":\\s*(-?[0-9.]+)").find(json)?.groupValues?.get(1) ?: error("no $key in $json")

    private fun normalizedReport(json: String): JsonObject {
        val reportLine = json.lineSequence().map(String::trim).single { it.startsWith("{") }
        val root = Json.parseToJsonElement(reportLine).jsonObject
        val evidence = root.getValue("evidence").jsonObject
        val inputSummary =
            root["inputSummary"]?.jsonObject?.let { summary ->
                JsonObject(summary - setOf("attemptedFeedTicks", "liveTicks"))
            }
        return JsonObject(
            root +
                ("evidence" to JsonObject(evidence - "command")) +
                (if (inputSummary != null) mapOf("inputSummary" to inputSummary) else emptyMap()),
        )
    }

    private fun seedRealEurUsdDay(dataRoot: Path) {
        val target = dataRoot.resolve("symbols/EURUSD/2024-01-10.csv.gz")
        Files.createDirectories(target.parent)
        requireNotNull(javaClass.getResourceAsStream("/parity/dukascopy/eurusd-2024-01-10.csv.gz")) {
            "real-data parity fixture is missing"
        }.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun realDataStrategyFile(dir: Path): Path {
        val strategy = dir.resolve("real-eurusd.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY real_eurusd VERSION 1
            SYMBOLS
                eurusd = BACKTEST:EURUSD EVERY 5m
            RULES
                WHEN ema(eurusd.close, 3) CROSSES ABOVE ema(eurusd.close, 9)
                THEN BUY eurusd SIZING 0.1 BRACKET { STOP LOSS PCT 0.1, TAKE PROFIT RR 2 }
                WHEN ema(eurusd.close, 3) CROSSES BELOW ema(eurusd.close, 9)
                THEN CLOSE eurusd
            """.trimIndent(),
        )
        return strategy
    }

    private fun runRealDataJson(
        strategy: Path,
        dataRoot: Path,
        extra: List<String>,
    ): String {
        val output = ByteArrayOutputStream()
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(output))
                BacktestCommand(
                    Args(
                        (
                            listOf(
                                "backtest",
                                strategy.toString(),
                                "--from",
                                "2024-01-10",
                                "--to",
                                "2024-01-11",
                                "--data-root",
                                dataRoot.toString(),
                                "--no-fetch",
                                "--allow-incomplete",
                                "--json",
                            ) + extra
                        ).toTypedArray(),
                    ),
                ).run()
            } finally {
                System.setOut(original)
            }
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        return output.toString()
    }

    @Test
    fun `tick-resolved fills match a full-tick replay byte-for-byte`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        val from = "2024-01-02"
        val to = "2024-01-07"
        seedTicks(dataRoot, days = 5)
        // Build the 15m bar store the --bars tier drives off (ticks already seeded for slicing).
        val build =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        "XAUUSD",
                        "--tf",
                        "15m",
                        "--from",
                        from,
                        "--to",
                        to,
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run()
        assertThat(build).isEqualTo(ExitCodes.SUCCESS)

        val fullTick = runJson(dir, dataRoot, from, to, extra = emptyList())
        val resolved = runJson(dir, dataRoot, from, to, extra = listOf("--bars", "--tick-fills"))

        // The whole point: a bracket strategy on a volatile path actually trades and hits brackets.
        assertThat(field(fullTick, "trades").toInt()).isGreaterThan(0)
        // Complete semantic report parity. Only the invocation command differs by design because
        // one run includes --bars --tick-fills; no computed or model-evidence field is removed.
        assertThat(normalizedReport(resolved)).isEqualTo(normalizedReport(fullTick))
    }

    @Test
    fun `tick-resolved fills match full-tick replay across a real EURUSD day`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        seedRealEurUsdDay(dataRoot)
        val build =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        "EURUSD",
                        "--tf",
                        "5m",
                        "--from",
                        "2024-01-10",
                        "--to",
                        "2024-01-11",
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run()
        assertThat(build).isEqualTo(ExitCodes.SUCCESS)

        val strategy = realDataStrategyFile(dir)
        val fullTick = runRealDataJson(strategy, dataRoot, extra = emptyList())
        val resolved = runRealDataJson(strategy, dataRoot, extra = listOf("--bars", "--tick-fills"))

        assertThat(field(fullTick, "trades").toInt()).isGreaterThan(0)
        assertThat(normalizedReport(resolved)).isEqualTo(normalizedReport(fullTick))
    }

    private fun instrumentsFile(dir: Path): Path {
        val f = dir.resolve("instruments.yaml")
        Files.writeString(
            f,
            """
            instruments:
              - qktSymbol: BACKTEST:XAUUSD
                contractSize: 100
                volumeStep: 0.01
                volumeMin: 0.01
                pointSize: 0.001
                digits: 3
                tradeStopsLevelPoints: 0
            """.trimIndent(),
        )
        return f
    }

    private fun seedSym(
        dataRoot: Path,
        symbol: String,
        days: Int,
        base: Double,
        amp: Double,
        period: Double,
    ) {
        val start =
            LocalDate
                .parse("2024-01-02")
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        (0 until days * 1440)
            .map { m -> Tick(symbol, Money.of("%.3f".format(base + amp * sin(m / period))), start + m * 60_000L) }
            .groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.timestamp), ZoneOffset.UTC) }
            .forEach { (day, dayTicks) ->
                val f = dataRoot.resolve("symbols").resolve(symbol).resolve("$day.bin")
                Files.createDirectories(f.parent)
                BinaryTickWriter().write(f, symbol, dayTicks)
            }
    }

    private fun twoSymbolStrategy(dir: Path): Path {
        val s = dir.resolve("two.qkt")
        Files.writeString(
            s,
            """
            STRATEGY two VERSION 1
            SYMBOLS
                a = BACKTEST:XAUUSD EVERY 15m
                b = BACKTEST:XAGUSD EVERY 15m
            RULES
                WHEN ema(a.close, 3) CROSSES ABOVE ema(a.close, 9) AND b.close > ema(b.close, 9)
                THEN BUY a SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
                WHEN ema(a.close, 3) CROSSES BELOW ema(a.close, 9)
                THEN CLOSE a
            """.trimIndent(),
        )
        return s
    }

    private fun bothTradeStrategy(dir: Path): Path {
        val s = dir.resolve("both.qkt")
        Files.writeString(
            s,
            """
            STRATEGY both VERSION 1
            SYMBOLS
                a = BACKTEST:XAUUSD EVERY 15m
                b = BACKTEST:XAGUSD EVERY 15m
            RULES
                WHEN ema(a.close, 3) CROSSES ABOVE ema(a.close, 9)
                THEN BUY a SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
                WHEN ema(a.close, 3) CROSSES BELOW ema(a.close, 9) THEN CLOSE a
                WHEN ema(b.close, 3) CROSSES ABOVE ema(b.close, 9)
                THEN BUY b SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
                WHEN ema(b.close, 3) CROSSES BELOW ema(b.close, 9) THEN CLOSE b
            """.trimIndent(),
        )
        return s
    }

    @Test
    fun `tick-resolved fills emit the same trades in the same order for a two-symbol strategy`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        val from = "2024-01-02"
        val to = "2024-01-07"
        seedSym(dataRoot, "XAUUSD", 5, 1850.0, 30.0, 7.0)
        seedSym(dataRoot, "XAGUSD", 5, 24.0, 0.4, 5.0)
        for (sym in listOf("XAUUSD", "XAGUSD")) {
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        sym,
                        "--tf",
                        "15m",
                        "--from",
                        from,
                        "--to",
                        to,
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run().also { assertThat(it).isEqualTo(ExitCodes.SUCCESS) }
        }

        fun trades(
            reportDir: Path,
            extra: List<String>,
        ): List<String> {
            val out = ByteArrayOutputStream()
            val orig = System.out
            val code =
                try {
                    System.setOut(PrintStream(out))
                    BacktestCommand(
                        Args(
                            (
                                listOf(
                                    "backtest",
                                    bothTradeStrategy(dir).toString(),
                                    "--from",
                                    from,
                                    "--to",
                                    to,
                                    "--data-root",
                                    dataRoot.toString(),
                                    "--no-fetch",
                                    "--allow-incomplete",
                                    "--json",
                                    "--report-dir",
                                    reportDir.toString(),
                                ) + extra
                            ).toTypedArray(),
                        ),
                    ).run()
                } finally {
                    System.setOut(orig)
                }
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
            // trades.csv: timestamp+symbol columns are enough to assert order (col 0 ts, col 2 symbol)
            return Files.readAllLines(reportDir.resolve("trades.csv")).drop(1).map {
                val c = it.split(",")
                "${c[0]},${c[2]},${c[3]}"
            }
        }

        val ft = trades(dir.resolve("ft"), emptyList())
        val tf = trades(dir.resolve("tf"), listOf("--bars", "--tick-fills"))
        assertThat(ft).isNotEmpty
        assertThat(tf).isEqualTo(ft) // same trades, same chronological order
    }

    @Test
    fun `tick-resolved fills match a full-tick replay for a multi-symbol strategy`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        val from = "2024-01-02"
        val to = "2024-01-07"
        seedSym(dataRoot, "XAUUSD", 5, 1850.0, 30.0, 7.0)
        seedSym(dataRoot, "XAGUSD", 5, 24.0, 0.4, 5.0) // different path + period -> cross-symbol ordering matters
        for (sym in listOf("XAUUSD", "XAGUSD")) {
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        sym,
                        "--tf",
                        "15m",
                        "--from",
                        from,
                        "--to",
                        to,
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run().also { assertThat(it).isEqualTo(ExitCodes.SUCCESS) }
        }

        fun run(extra: List<String>): String {
            val out = ByteArrayOutputStream()
            val orig = System.out
            val code =
                try {
                    System.setOut(PrintStream(out))
                    BacktestCommand(
                        Args(
                            (
                                listOf(
                                    "backtest",
                                    twoSymbolStrategy(dir).toString(),
                                    "--from",
                                    from,
                                    "--to",
                                    to,
                                    "--data-root",
                                    dataRoot.toString(),
                                    "--no-fetch",
                                    "--allow-incomplete",
                                    "--json",
                                ) + extra
                            ).toTypedArray(),
                        ),
                    ).run()
                } finally {
                    System.setOut(orig)
                }
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
            return out.toString()
        }

        val fullTick = run(emptyList())
        val resolved = run(listOf("--bars", "--tick-fills"))
        assertThat(field(fullTick, "trades").toInt()).isGreaterThan(0)
        assertThat(field(resolved, "trades")).isEqualTo(field(fullTick, "trades"))
        assertThat(field(resolved, "totalPnL")).isEqualTo(field(fullTick, "totalPnL"))
    }

    @Test
    fun `tick-resolved fills match a full-tick replay under mt5-sim`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        val from = "2024-01-02"
        val to = "2024-01-07"
        seedTicks(dataRoot, days = 5)
        DataCommand(
            Args(
                arrayOf(
                    "data",
                    "build-bars",
                    "XAUUSD",
                    "--tf",
                    "15m",
                    "--from",
                    from,
                    "--to",
                    to,
                    "--data-root",
                    dataRoot.toString(),
                ),
            ),
        ).run().also { assertThat(it).isEqualTo(ExitCodes.SUCCESS) }

        val mt5 = listOf("--broker", "mt5-sim", "--instruments", instrumentsFile(dir).toString())
        val fullTick = runJson(dir, dataRoot, from, to, extra = mt5)
        val resolved = runJson(dir, dataRoot, from, to, extra = mt5 + listOf("--bars", "--tick-fills"))

        assertThat(field(fullTick, "trades").toInt()).isGreaterThan(0)
        assertThat(field(resolved, "trades")).isEqualTo(field(fullTick, "trades"))
        assertThat(field(resolved, "totalPnL")).isEqualTo(field(fullTick, "totalPnL"))
        assertThat(field(resolved, "maxDrawdown")).isEqualTo(field(fullTick, "maxDrawdown"))
    }
}
