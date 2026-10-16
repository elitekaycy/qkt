package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `qkt data verify SOURCE:SYMBOL` checks a series' stored bars the way `qkt data verify SYMBOL` checks
 * ticks: imported CSV bars are the data most users bring, and they need a check too.
 */
class DataVerifyBarsTest {
    private val header = "timestamp,open,high,low,close,volume"

    private fun ms(
        day: String,
        minute: Int,
    ) = LocalDate
        .parse(day)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli() + minute * 60_000L

    private fun seed(
        root: Path,
        day: String,
        rows: List<String>,
    ) {
        val dir = root.resolve("bars/MYBROKER/EURUSD/1m")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$day.csv"), (listOf(header) + rows).joinToString("\n") + "\n")
    }

    private fun bar(
        day: String,
        minute: Int,
        open: String = "1.1",
        high: String = "1.2",
        low: String = "1.0",
        close: String = "1.1",
    ) = "${ms(day, minute)},$open,$high,$low,$close,5"

    private fun verify(
        root: Path,
        vararg extra: String,
    ): Pair<Int, List<String>> {
        val out = ByteArrayOutputStream()
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(out))
                DataCommand(
                    Args(arrayOf("data", "verify", "MYBROKER:EURUSD", "--data-root", root.toString(), *extra)),
                ).run()
            } finally {
                System.setOut(original)
            }
        return code to out.toString().lines()
    }

    @Test
    fun `sound bars pass with their count and largest gap`(
        @TempDir root: Path,
    ) {
        seed(root, "2026-10-13", (0..9).map { bar("2026-10-13", it) } + bar("2026-10-13", 15))

        val (code, lines) = verify(root)

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(lines).contains("  2026-10-13  bars=11  maxGap=6m  ok")
        assertThat(lines).anyMatch { it.startsWith("qkt data verify: done — 1 days, 11 bars, 0 flagged") }
    }

    @Test
    fun `impossible prices, off-grid bars and a missing weekday are flagged`(
        @TempDir root: Path,
    ) {
        seed(
            root,
            "2026-10-12",
            listOf(bar("2026-10-12", 0, high = "1.05"), "${ms("2026-10-12", 1) + 30_000},1.1,1.2,1.0,1.1,5"),
        )
        seed(root, "2026-10-14", listOf(bar("2026-10-14", 0)))

        val (code, lines) = verify(root)

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(lines).anyMatch {
            it.startsWith("  2026-10-12  bars=2") &&
                it.contains("1 off the 1m grid") &&
                it.contains("1 with impossible prices")
        }
        assertThat(lines).anyMatch { it.startsWith("  2026-10-13  MISSING") }
    }

    @Test
    fun `a file the backtest reader refuses is reported as unreadable`(
        @TempDir root: Path,
    ) {
        seed(root, "2026-10-13", listOf(bar("2026-10-13", 5), bar("2026-10-13", 2)))

        val (code, lines) = verify(root)

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(lines).anyMatch { it.startsWith("  2026-10-13  unreadable:") && it.contains("bar times must rise") }
    }

    @Test
    fun `a timeframe that is not stored is named`(
        @TempDir root: Path,
    ) {
        seed(root, "2026-10-13", listOf(bar("2026-10-13", 0)))

        assertThat(verify(root, "--tf", "5m").first).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `built bars in the --bars tier are checked as well, and an empty series is an error`(
        @TempDir root: Path,
    ) {
        val day = java.time.LocalDate.parse("2026-10-13")
        val bars =
            (0..2).map { m ->
                com.qkt.marketdata.Candle(
                    "MYBROKER:EURUSD",
                    java.math.BigDecimal("1.1"),
                    java.math.BigDecimal("1.2"),
                    java.math.BigDecimal("1.0"),
                    java.math.BigDecimal("1.1"),
                    java.math.BigDecimal("5"),
                    ms("2026-10-13", m),
                    ms("2026-10-13", m + 1),
                )
            }
        com.qkt.marketdata.store.BinaryBarStore(root).writeDay(
            "MYBROKER",
            "EURUSD",
            com.qkt.candles.TimeWindow.ONE_MINUTE,
            day,
            bars,
        )

        val (code, lines) = verify(root)

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(lines).anyMatch { it.contains("1m .bin bars, the --bars tier (1 day files)") }
        assertThat(lines).contains("  2026-10-13  bars=3  maxGap=1m  ok")
        assertThat(verify(root.resolve("elsewhere")).first).isEqualTo(ExitCodes.USER_ERROR)
    }
}
