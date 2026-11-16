package com.qkt.marketdata.store

import com.qkt.marketdata.store.DayCompleteness.Status
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionCoverageHolidayEdgeTest {
    @Test
    fun `a day that trades only after its holiday ends names when the holiday ends`() {
        val store =
            CoverageStore()
                .day("2024-12-24", "00:00-22:00")
                .day("2024-12-25", "22:00-24:00")
                .winterGold("2024-12-26")

        val days =
            SessionCoverage(
                com.qkt.common.TradingCalendar
                    .fxDefault(),
                "XAUUSD",
            ).classify(LocalDate.parse("2024-12-24"), LocalDate.parse("2024-12-26"), { store.days[it] }) { false }
                .associateBy { it.day.toString() }

        assertThat(days.getValue("2024-12-25").status).isEqualTo(Status.COMPLETE)
        assertThat(days.getValue("2024-12-25").detail).startsWith("holiday until")
    }
}
