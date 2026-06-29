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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin
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
                THEN BUY gold SIZING 0.1 BRACKET { STOP LOSS PCT 0.01, TAKE PROFIT RR 2 }
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
        // Byte-identical on every reported metric.
        assertThat(field(resolved, "trades")).isEqualTo(field(fullTick, "trades"))
        assertThat(field(resolved, "totalPnL")).isEqualTo(field(fullTick, "totalPnL"))
        assertThat(field(resolved, "maxDrawdown")).isEqualTo(field(fullTick, "maxDrawdown"))
    }
}
