package com.qkt.cli

import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Tick
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A backtest refused for missing tick days says whether built bars could stand in for them. */
class BacktestMissingTicksBarsLineTest {
    @TempDir
    lateinit var tmp: Path

    private val day = LocalDate.parse("2024-01-04")

    private fun dataRoot() = tmp.resolve("data")

    private fun tickFile() = dataRoot().resolve("symbols/XAUUSD/$day.bin")

    private fun seedTicks() {
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val ticks = (0 until 1440).map { m -> Tick("XAUUSD", Money.of("1850.00"), start + m * 60_000L) }
        Files.createDirectories(tickFile().parent)
        BinaryTickWriter().write(tickFile(), "XAUUSD", ticks)
    }

    private fun strategy(): String {
        val path = tmp.resolve("s.qkt")
        Files.writeString(
            path,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN gold.close > 0 THEN BUY gold SIZING 0.1
            """.trimIndent(),
        )
        return path.toString()
    }

    private fun backtest() =
        captureCliRun {
            BacktestCommand(
                Args(
                    arrayOf(
                        "backtest",
                        strategy(),
                        "--from",
                        "$day",
                        "--to",
                        "${day.plusDays(1)}",
                        "--data-root",
                        dataRoot().toString(),
                        "--no-fetch",
                        "--no-report",
                    ),
                ),
            ).run()
        }

    @Test
    fun `missing ticks with built bars for the window suggest rerunning with bars`() {
        seedTicks()
        val build = arrayOf("data", "build-bars", "XAUUSD", "--tf", "15m", "--data-root", dataRoot().toString())
        assertThat(captureCliRun { DataCommand(Args(build)).run() }.code).isEqualTo(ExitCodes.SUCCESS)
        Files.delete(tickFile())

        val run = backtest()

        assertThat(run.code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(run.err).contains("Bars exist for this window (15m, 1 of 1 days): rerun with --bars to use them")
    }

    @Test
    fun `missing ticks without built bars name the build-bars fix at the strategy timeframe`() {
        val run = backtest()

        assertThat(run.code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(
            run.err,
        ).contains("No built bars cover this window. Try qkt data build-bars XAUUSD --tf 1m to build them.")
    }
}
