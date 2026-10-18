package com.qkt.marketdata.store

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.store.DayCompleteness.Status
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionCoverageTest {
    private fun classify(
        store: CoverageStore,
        from: String,
        to: String,
        calendar: TradingCalendar = TradingCalendar.fxDefault(),
        storedLater: Boolean = false,
    ): Map<String, DayCompleteness> =
        SessionCoverage(calendar, "XAUUSD")
            .classify(LocalDate.parse(from), LocalDate.parse(to), { store.days[it] }) { storedLater }
            .associateBy { it.day.toString() }

    @Test
    fun `a full gold week is complete and the weekend is closed`() {
        val store =
            CoverageStore()
                .winterGold(
                    "2024-02-05",
                    "2024-02-06",
                    "2024-02-07",
                    "2024-02-08",
                ).day("2024-02-09", "00:00-21:59")
        store.day("2024-02-11", "23:00-24:00").winterGold("2024-02-12")

        val days = classify(store, "2024-02-05", "2024-02-12")

        assertThat(days.filterValues { it.status == Status.COMPLETE }.keys)
            .containsExactly(
                "2024-02-05",
                "2024-02-06",
                "2024-02-07",
                "2024-02-08",
                "2024-02-09",
                "2024-02-11",
                "2024-02-12",
            )
        assertThat(days.getValue("2024-02-10").status).isEqualTo(Status.NON_TRADING)
        assertThat(days.getValue("2024-02-10").detail).isEqualTo("closed")
    }

    @Test
    fun `an early close is a short session when later ticks are stored`() {
        // #1438: Presidents' Day 2024-02-19, no gold ticks from 18:30 UTC until the 23:00 reopen.
        val store = CoverageStore().winterGold("2024-02-16").day("2024-02-18", "23:00-24:00")
        store.day("2024-02-19", "00:00-18:30", "23:00-24:00").winterGold("2024-02-20")

        val day = classify(store, "2024-02-19", "2024-02-19").getValue("2024-02-19")

        assertThat(day.status).isEqualTo(Status.SHORT)
        assertThat(day.detail).isEqualTo("short session (last tick 18:29Z)")
        assertThat(day.covered).isTrue()
    }

    @Test
    fun `the last stored day ending early is a truncated fetch, not a short session`() {
        val store = CoverageStore().winterGold("2024-02-15").day("2024-02-16", "00:00-17:00")

        val day = classify(store, "2024-02-15", "2024-02-16").getValue("2024-02-16")

        assertThat(day.status).isEqualTo(Status.INCOMPLETE)
        assertThat(day.detail).isEqualTo("no ticks after 16:59Z (nothing stored later)")
        assertThat(classify(store, "2024-02-15", "2024-02-16", storedLater = true).getValue("2024-02-16").status)
            .isEqualTo(Status.SHORT)
    }

    @Test
    fun `a late open after a holiday is short and one past six hours is a hole`() {
        val store =
            CoverageStore()
                .winterGold(
                    "2023-12-22",
                ).day("2023-12-26", "02:00-22:00", "23:00-24:00")
                .winterGold("2023-12-27")
        val days = classify(store, "2023-12-22", "2023-12-27")
        assertThat(days.getValue("2023-12-25").status).isEqualTo(Status.SHORT) // the 22:00 reopen came at 02:00
        assertThat(days.getValue("2023-12-25").detail).isEqualTo("short session (first tick 12-26 02:00Z)")
        assertThat(days.getValue("2023-12-26").status).isEqualTo(Status.SHORT)

        store.day("2023-12-26", "05:00-22:00", "23:00-24:00")
        assertThat(
            classify(store, "2023-12-22", "2023-12-27").getValue("2023-12-26").status,
        ).isEqualTo(Status.INCOMPLETE)
    }

    @Test
    fun `an interior hole over 90 minutes is incomplete and an hour-long break is not`() {
        val store =
            CoverageStore()
                .winterGold(
                    "2024-03-05",
                    "2024-03-07",
                ).day("2024-03-06", "00:00-10:00", "12:00-22:00", "23:00-24:00")

        val day = classify(store, "2024-03-05", "2024-03-07").getValue("2024-03-06")
        assertThat(day.status).isEqualTo(Status.INCOMPLETE)
        assertThat(day.detail).isEqualTo("no ticks 09:59Z-12:00Z")
        assertThat(day.emptyHours).containsExactly(9, 10, 11)

        store.day("2024-03-06", "00:00-10:00", "11:00-22:00", "23:00-24:00")
        assertThat(classify(store, "2024-03-05", "2024-03-07").getValue("2024-03-06").status).isEqualTo(Status.COMPLETE)
    }

    @Test
    fun `a whole weekday with no ticks stays missing`() {
        // 2023-11-24 (Black Friday) is not a closure: without a file it is missing, the neighbours complete.
        val store =
            CoverageStore()
                .winterGold(
                    "2023-11-22",
                    "2023-11-23",
                ).day("2023-11-26", "23:00-24:00")
                .winterGold("2023-11-27")

        val days = classify(store, "2023-11-22", "2023-11-27")

        assertThat(days.getValue("2023-11-24").status).isEqualTo(Status.MISSING)
        assertThat(days.filterValues { it.hole }.keys).containsExactly("2023-11-24")
    }

    @Test
    fun `Good Friday and Christmas need no ticks and the holiday edge of the day before is not a hole`() {
        // #1438: 2024-03-28's hours 21-23 UTC belong to the closed Good Friday session.
        val store =
            CoverageStore()
                .summerGold(
                    "2024-03-27",
                ).day("2024-03-28", "00:00-21:00")
                .day("2024-03-31", "22:00-24:00")
                .summerGold("2024-04-01")

        val days = classify(store, "2024-03-27", "2024-04-01")

        assertThat(days.getValue("2024-03-28").status).isEqualTo(Status.COMPLETE)
        assertThat(days.getValue("2024-03-28").detail).isEqualTo("holiday from 21:00Z")
        assertThat(days.getValue("2024-03-29").holiday).isTrue()
        assertThat(days.values.filter { it.hole }).isEmpty()
    }
}
