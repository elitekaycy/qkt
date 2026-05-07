package com.qkt.common

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingCalendarTradingPeriodsPerYearTest {
    @Test
    fun `crypto returns 525960 for 1 minute window`() {
        val periods = TradingCalendar.crypto().tradingPeriodsPerYear(TimeWindow.ONE_MINUTE)
        assertThat(periods).isEqualByComparingTo(BigDecimal("525960"))
    }

    @Test
    fun `crypto scales inversely with window size for 5 minute window`() {
        val periods = TradingCalendar.crypto().tradingPeriodsPerYear(TimeWindow.FIVE_MINUTES)
        assertThat(periods).isEqualByComparingTo(BigDecimal("105192"))
    }

    @Test
    fun `crypto returns 8766 for 1 hour window`() {
        val periods = TradingCalendar.crypto().tradingPeriodsPerYear(TimeWindow.ONE_HOUR)
        assertThat(periods).isEqualByComparingTo(BigDecimal("8766"))
    }
}
