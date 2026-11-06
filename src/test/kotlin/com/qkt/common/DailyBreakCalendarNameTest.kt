package com.qkt.common

import java.time.LocalTime
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DailyBreakCalendarNameTest {
    @Test
    fun `a daily break calendar prints as its name`() {
        val cal =
            DailyBreakCalendar(FxCalendar, LocalTime.of(17, 0), LocalTime.of(18, 0), ZoneId.of("America/New_York"))

        assertThat(
            cal.toString(),
        ).isEqualTo(cal.name).isEqualTo("${FxCalendar.name} pause 17:00-18:00 America/New_York")
    }
}
