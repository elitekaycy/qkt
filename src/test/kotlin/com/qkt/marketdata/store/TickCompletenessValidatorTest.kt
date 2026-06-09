package com.qkt.marketdata.store

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.CsvTickFeed
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TickCompletenessValidatorTest {
    private fun writeDay(
        root: Path,
        symbol: String,
        day: LocalDate,
        hoursWithTicks: List<Int>,
    ) {
        val dir = root.resolve("symbols").resolve(symbol)
        Files.createDirectories(dir)
        val lines = mutableListOf(CsvTickFeed.EXPECTED_HEADER)
        val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        for (h in hoursWithTicks) {
            lines += "${dayStart + h * 3_600_000L},$symbol,,,100.00,100.02,1.00,1.00"
        }
        Files.writeString(dir.resolve("$day.csv"), lines.joinToString("\n") + "\n")
    }

    @Test
    fun `a fully covered fx week day is complete`(
        @TempDir tmp: Path,
    ) {
        // Wednesday 2024-03-06: FX open all day. Write a tick in every hour 0..23.
        writeDay(tmp, "EURUSD", LocalDate.of(2024, 3, 6), (0..23).toList())
        val store = DefaultDataStore(root = tmp)
        val report =
            TickCompletenessValidator.validate(
                store = store,
                symbol = "EURUSD",
                from = LocalDate.of(2024, 3, 6),
                to = LocalDate.of(2024, 3, 6),
                calendar = TradingCalendar.fxDefault(),
            )
        assertThat(report.hasHoles).isFalse()
    }

    @Test
    fun `a weekend day expects no data`(
        @TempDir tmp: Path,
    ) {
        // Saturday 2024-03-09: FX closed. No file at all — must not be a hole.
        val store = DefaultDataStore(root = tmp)
        val report =
            TickCompletenessValidator.validate(
                store,
                "EURUSD",
                LocalDate.of(2024, 3, 9),
                LocalDate.of(2024, 3, 9),
                TradingCalendar.fxDefault(),
            )
        assertThat(report.hasHoles).isFalse()
    }

    @Test
    fun `a missing trading day is a hole`(
        @TempDir tmp: Path,
    ) {
        val store = DefaultDataStore(root = tmp)
        val report =
            TickCompletenessValidator.validate(
                store,
                "EURUSD",
                LocalDate.of(2024, 3, 6),
                LocalDate.of(2024, 3, 6),
                TradingCalendar.fxDefault(),
            )
        assertThat(report.hasHoles).isTrue()
        assertThat(report.days.single().status).isEqualTo(DayCompleteness.Status.MISSING)
    }

    @Test
    fun `a single interior empty hour is tolerated as the daily maintenance break`(
        @TempDir tmp: Path,
    ) {
        // Mirrors real dukascopy XAUUSD: every hour has ticks except 21:00-22:00 UTC, gold's
        // daily halt. One empty interior hour is the routine break, not a failed fetch.
        writeDay(tmp, "EURUSD", LocalDate.of(2024, 3, 6), (0..23).filter { it != 21 })
        val store = DefaultDataStore(root = tmp)
        val report =
            TickCompletenessValidator.validate(
                store,
                "EURUSD",
                LocalDate.of(2024, 3, 6),
                LocalDate.of(2024, 3, 6),
                TradingCalendar.fxDefault(),
            )
        assertThat(report.hasHoles).isFalse()
        assertThat(report.days.single().status).isEqualTo(DayCompleteness.Status.COMPLETE)
    }

    @Test
    fun `two or more interior empty hours is incomplete`(
        @TempDir tmp: Path,
    ) {
        // Beyond the single tolerated break: a real fetch hole drops more than one hour.
        writeDay(tmp, "EURUSD", LocalDate.of(2024, 3, 6), (0..23).filter { it != 12 && it != 14 })
        val store = DefaultDataStore(root = tmp)
        val report =
            TickCompletenessValidator.validate(
                store,
                "EURUSD",
                LocalDate.of(2024, 3, 6),
                LocalDate.of(2024, 3, 6),
                TradingCalendar.fxDefault(),
            )
        assertThat(report.hasHoles).isTrue()
        assertThat(report.days.single().status).isEqualTo(DayCompleteness.Status.INCOMPLETE)
        assertThat(report.days.single().emptyHours).contains(12, 14)
    }
}
