package com.qkt.cli

import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Tick
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataCommandTest {
    private val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"

    private fun seedDay(
        root: Path,
        symbol: String,
        day: String,
        lines: List<String>,
    ) {
        val dir = root.resolve("symbols").resolve(symbol)
        Files.createDirectories(dir)
        Files.write(dir.resolve("$day.csv"), listOf(header) + lines)
    }

    @Test
    fun `verify exits non-zero when a cached day is empty`(
        @TempDir root: Path,
    ) {
        seedDay(root, "XAUUSD", "2024-01-01", listOf("0,XAUUSD,2000,,,,,"))
        seedDay(root, "XAUUSD", "2024-01-02", emptyList()) // header-only → flagged EMPTY
        val code =
            DataCommand(Args(arrayOf("data", "verify", "XAUUSD", "--data-root", root.toString()))).run()
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `verify exits zero when every cached day has ticks`(
        @TempDir root: Path,
    ) {
        seedDay(
            root,
            "XAUUSD",
            "2024-01-01",
            listOf("1704067200000,XAUUSD,2000,,,,,", "1704067260000,XAUUSD,2001,,,,,"),
        )
        val code =
            DataCommand(Args(arrayOf("data", "verify", "XAUUSD", "--data-root", root.toString()))).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }

    @Test
    fun `verify reports a user error when the symbol has no cached data`(
        @TempDir root: Path,
    ) {
        val code =
            DataCommand(Args(arrayOf("data", "verify", "NOPE", "--data-root", root.toString()))).run()
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `verify reads binary tick days and flags a corrupt one`(
        @TempDir root: Path,
    ) {
        val dir = root.resolve("symbols").resolve("XAUUSD")
        Files.createDirectories(dir)
        val ticks =
            listOf(
                Tick("XAUUSD", Money.of("2000"), 1_704_067_200_000L),
                Tick("XAUUSD", Money.of("2001"), 1_704_067_260_000L),
            )
        BinaryTickWriter().write(dir.resolve("2024-01-01.bin"), "XAUUSD", ticks)
        val verify = { DataCommand(Args(arrayOf("data", "verify", "XAUUSD", "--data-root", root.toString()))).run() }
        assertThat(verify()).isEqualTo(ExitCodes.SUCCESS)

        val whole = Files.readAllBytes(dir.resolve("2024-01-01.bin"))
        Files.write(dir.resolve("2024-01-02.bin"), whole.copyOf(whole.size - 9))

        assertThat(verify()).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `an empty Saturday is a closed day for a market that never trades on one, a hole for one that does`(
        @TempDir root: Path,
    ) {
        // 2024-01-05 Friday, 2024-01-06 Saturday (empty, as qkt fetch writes it), 2024-01-07 Sunday.
        seedDay(root, "EURUSD", "2024-01-05", listOf("1704412800000,EURUSD,1.1,,,,,"))
        seedDay(root, "EURUSD", "2024-01-06", emptyList())
        seedDay(root, "EURUSD", "2024-01-07", listOf("1704661200000,EURUSD,1.1,,,,,"))
        val verify = { s: String -> DataCommand(Args(arrayOf("data", "verify", s, "--data-root", root.toString()))).run() }
        assertThat(verify("EURUSD")).isEqualTo(ExitCodes.SUCCESS)

        seedDay(root, "BTCUSD", "2024-01-06", emptyList())
        seedDay(root, "BTCUSD", "2024-01-13", listOf("1705104000000,BTCUSD,40000,,,,,"))
        assertThat(verify("BTCUSD")).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `a converted day is checked once, and a bin that disagrees with its csv is flagged`(
        @TempDir root: Path,
    ) {
        seedDay(root, "XAUUSD", "2024-01-01", listOf("1704067200000,XAUUSD,2000,,,,,", "1704067260000,XAUUSD,2001,,,,,"))
        val dir = root.resolve("symbols").resolve("XAUUSD")
        val out = java.io.ByteArrayOutputStream()
        val verify = {
            out.reset()
            val original = System.out
            try {
                System.setOut(java.io.PrintStream(out))
                DataCommand(Args(arrayOf("data", "verify", "XAUUSD", "--data-root", root.toString()))).run()
            } finally {
                System.setOut(original)
            }
        }
        BinaryTickWriter().write(
            dir.resolve("2024-01-01.bin"),
            "XAUUSD",
            listOf(
                Tick("XAUUSD", Money.of("2000"), 1_704_067_200_000L),
                Tick("XAUUSD", Money.of("2001"), 1_704_067_260_000L),
            ),
        )
        assertThat(verify()).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out.toString()).contains("done — 1 days, 2 ticks, 0 flagged")

        BinaryTickWriter().write(dir.resolve("2024-01-01.bin"), "XAUUSD", listOf(Tick("XAUUSD", Money.of("2000"), 1_704_067_200_000L)))
        assertThat(verify()).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(out.toString()).contains("hold different ticks (1 vs 2)")
    }
}
