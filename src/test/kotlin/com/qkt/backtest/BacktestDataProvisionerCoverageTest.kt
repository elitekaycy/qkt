package com.qkt.backtest

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.store.DefaultDataStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestDataProvisionerCoverageTest {
    /** Writes [day]'s gold ticks, one a minute through the UTC minutes in [minutes]. */
    private fun seed(
        root: Path,
        day: String,
        minutes: IntRange,
        vararg more: IntRange,
    ) {
        val dir = root.resolve("symbols").resolve("XAUUSD")
        Files.createDirectories(dir)
        val start =
            LocalDate
                .parse(day)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val rows =
            (listOf(minutes) + more).flatMap { r ->
                r.map { "${start + it * 60_000L},XAUUSD,,,2000.0,2000.2,1.0,1.0" }
            }
        Files.write(dir.resolve("$day.csv"), listOf(CsvTickFeed.EXPECTED_HEADER) + rows)
    }

    private fun winter(
        root: Path,
        vararg days: String,
    ) = days.forEach { seed(root, it, 0 until 22 * 60, 23 * 60 until 24 * 60) }

    private fun ensure(
        root: Path,
        lines: MutableList<String>,
    ) = BacktestDataProvisioner(DefaultDataStore(root = root, fetcher = null)) { lines += it }.ensure(
        streams = listOf(ProvisionStream(broker = "BACKTEST", bareSymbol = "XAUUSD")),
        from = LocalDate.parse("2024-02-15"),
        to = LocalDate.parse("2024-02-20"),
        fetchEnabled = false,
        allowIncomplete = false,
        calendarFor = { TradingCalendar.fxDefault() },
    )

    @Test
    fun `a holiday short session passes with one info line`(
        @TempDir root: Path,
    ) {
        // #1438: Presidents' Day 2024-02-19, gold stops at 18:30 UTC and reopens 23:00.
        winter(root, "2024-02-15", "2024-02-16", "2024-02-20", "2024-02-21")
        seed(root, "2024-02-18", 23 * 60 until 24 * 60)
        seed(root, "2024-02-19", 0 until 18 * 60 + 30, 23 * 60 until 24 * 60)
        val lines = mutableListOf<String>()

        ensure(root, lines)

        assertThat(
            lines,
        ).contains("qkt: tick coverage XAUUSD 5/5 trading days (short sessions, holiday hours: 2024-02-19)")
    }

    @Test
    fun `missing and incomplete days are told apart, with the empty stretch and the fixes`(
        @TempDir root: Path,
    ) {
        winter(root, "2024-02-15", "2024-02-20", "2024-02-21")
        seed(root, "2024-02-18", 23 * 60 until 24 * 60)
        seed(root, "2024-02-16", 0 until 9 * 60, 12 * 60 until 22 * 60)

        val thrown = catchThrowable { ensure(root, mutableListOf()) }

        assertThat(thrown).isInstanceOf(IncompleteDataException::class.java)
        assertThat(thrown.message)
            .contains("incomplete XAUUSD tick data for 2024-02-15 to 2024-02-20 (3 of 5 trading days).")
            .contains("Missing: 2024-02-19 (1 range, 1 day): no ticks stored for these trading days.")
            .contains("Incomplete: 2024-02-16 (no ticks 08:59Z-12:00Z).")
            .contains("Or let qkt download ticks: drop --no-fetch.")
            .contains("qkt data import <file> --as XAUUSD")
            .contains("--allow-incomplete")
    }
}
