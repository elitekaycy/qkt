package com.qkt.cli

import com.qkt.common.TradingCalendar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestCalendarsTest {
    @Test
    fun `equity indices resolve to the nyse calendar`() {
        val cals = BacktestContext.defaultCalendars()
        assertThat(cals.calendarFor("SPX")).isEqualTo(TradingCalendar.nyse())
        assertThat(cals.calendarFor("NDX")).isEqualTo(TradingCalendar.nyse())
        assertThat(cals.calendarFor("DJI")).isEqualTo(TradingCalendar.nyse())
        assertThat(cals.calendarFor("RUT")).isEqualTo(TradingCalendar.nyse())
    }

    @Test
    fun `the dollar index uses the fx default since it trades the fx week`() {
        assertThat(BacktestContext.defaultCalendars().calendarFor("DXY"))
            .isEqualTo(TradingCalendar.fxDefault())
    }
}
