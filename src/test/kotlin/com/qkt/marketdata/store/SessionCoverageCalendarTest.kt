package com.qkt.marketdata.store

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.store.DayCompleteness.Status
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionCoverageCalendarTest {
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
    fun `the week either side of a daylight-saving change is complete with the break on its new hour`() {
        // US clocks moved 2024-03-10 and 2024-11-03: gold's break is 22:00 UTC before March and after November.
        val march =
            CoverageStore()
                .winterGold(
                    "2024-03-07",
                    "2024-03-08",
                ).day("2024-03-10", "22:00-24:00")
                .summerGold("2024-03-11", "2024-03-12")
        assertThat(classify(march, "2024-03-07", "2024-03-12").values.filter { it.hole }).isEmpty()

        val november =
            CoverageStore()
                .summerGold(
                    "2024-10-31",
                    "2024-11-01",
                ).day("2024-11-03", "23:00-24:00")
                .winterGold("2024-11-04", "2024-11-05")
        assertThat(classify(november, "2024-10-31", "2024-11-05").values.filter { it.hole }).isEmpty()

        // A feed keeping the summer break hour in a winter week is still one hour short of a hole.
        val summerBreak = CoverageStore().winterGold("2024-11-04").summerGold("2024-11-05").winterGold("2024-11-06")
        assertThat(classify(summerBreak, "2024-11-04", "2024-11-06").getValue("2024-11-05").status)
            .isEqualTo(Status.COMPLETE)
    }

    @Test
    fun `crypto has no holidays, no short sessions and a hole across midnight is caught`() {
        val crypto = TradingCalendar.crypto()
        val store =
            CoverageStore()
                .day(
                    "2024-03-29",
                    "00:00-23:00",
                ).day("2024-03-30", "01:00-24:00")
                .day("2024-03-31", "00:00-24:00")

        val days = classify(store, "2024-03-29", "2024-03-31", crypto)

        assertThat(days.getValue("2024-03-29").status).isEqualTo(Status.INCOMPLETE)
        assertThat(days.getValue("2024-03-30").status).isEqualTo(Status.INCOMPLETE)
        assertThat(days.getValue("2024-03-31").status).isEqualTo(Status.COMPLETE)
        assertThat(classify(CoverageStore(), "2024-03-30", "2024-03-30", crypto).getValue("2024-03-30").status)
            .isEqualTo(Status.MISSING)
    }
}
