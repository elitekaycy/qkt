package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat

/** Synthetic XAUUSD ticks, the bracket strategy and the backtest runs the tick-resolved parity tests compare. */
internal object TickResolvedParityFixtures {
    // 1 tick/min, a fast high-amplitude sine so a 15m bar (15 ticks) swings far enough to hit a 1%
    // stop / 2R target intrabar — the case that separates tick-resolved from plain --bars.
    fun ticksFor(days: Int): List<Tick> {
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

    fun seedTicks(
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

    fun strategyFile(dir: Path): Path {
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

    fun runJson(
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

    fun field(
        json: String,
        key: String,
    ): String = Regex("\"$key\":\\s*(-?[0-9.]+)").find(json)?.groupValues?.get(1) ?: error("no $key in $json")

    fun normalizedReport(json: String): JsonObject {
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

    fun instrumentsFile(dir: Path): Path {
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
}
