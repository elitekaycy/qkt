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
        seedDay(root, "XAUUSD", "2024-01-01", listOf("0,XAUUSD,2000,,,,,", "60000,XAUUSD,2001,,,,,"))
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
        val ticks = listOf(Tick("XAUUSD", Money.of("2000"), 0L), Tick("XAUUSD", Money.of("2001"), 60_000L))
        BinaryTickWriter().write(dir.resolve("2024-01-01.bin"), "XAUUSD", ticks)
        val verify = { DataCommand(Args(arrayOf("data", "verify", "XAUUSD", "--data-root", root.toString()))).run() }
        assertThat(verify()).isEqualTo(ExitCodes.SUCCESS)

        val whole = Files.readAllBytes(dir.resolve("2024-01-01.bin"))
        Files.write(dir.resolve("2024-01-02.bin"), whole.copyOf(whole.size - 9))

        assertThat(verify()).isEqualTo(ExitCodes.USER_ERROR)
    }
}
