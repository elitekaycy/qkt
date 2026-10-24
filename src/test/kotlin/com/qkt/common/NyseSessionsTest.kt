package com.qkt.common

import com.qkt.candles.TimeWindow
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NyseSessionsTest {
    private val cal = TradingCalendar.nyse()

    private fun open(iso: String) = cal.isInSession("AAPL", Instant.parse(iso))

    @Test
    fun `the friday after thanksgiving closes at 13_00 new york`() {
        assertThat(open("2024-11-29T17:59:00Z")).isTrue()
        assertThat(open("2024-11-29T18:00:00Z")).isFalse()
        assertThat(cal.sessionRange("AAPL", Instant.parse("2024-11-29T15:00:00Z")).to)
            .isEqualTo(Instant.parse("2024-11-29T18:00:00Z"))
    }

    @Test
    fun `july 3 on a weekday before the holiday closes early in daylight time`() {
        assertThat(open("2024-07-03T16:59:00Z")).isTrue()
        assertThat(open("2024-07-03T17:00:00Z")).isFalse()
        assertThat(open("2023-07-03T16:59:00Z")).isTrue()
        assertThat(open("2023-07-03T17:00:00Z")).isFalse()
    }

    @Test
    fun `christmas eve on a weekday closes early in standard time`() {
        assertThat(open("2024-12-24T17:59:00Z")).isTrue()
        assertThat(open("2024-12-24T18:00:00Z")).isFalse()
    }

    @Test
    fun `a friday july 3 is the observed holiday and the thursday before trades full hours`() {
        assertThat(open("2026-07-03T15:00:00Z")).isFalse()
        assertThat(open("2026-07-02T19:59:00Z")).isTrue()
    }

    @Test
    fun `a friday christmas eve is the observed christmas holiday`() {
        assertThat(open("2021-12-24T15:00:00Z")).isFalse()
        assertThat(open("2021-12-23T20:59:00Z")).isTrue()
    }

    @Test
    fun `a saturday new year is not observed on the friday before`() {
        assertThat(open("2021-12-31T15:00:00Z")).isTrue()
    }

    @Test
    fun `special closures are closed sessions`() {
        for (day in listOf(
            "2001-09-11",
            "2001-09-14",
            "2004-06-11",
            "2007-01-02",
            "2012-10-29",
            "2012-10-30",
            "2018-12-05",
            "2025-01-09",
        )) {
            val noon = Instant.parse("${day}T17:00:00Z")
            assertThat(cal.isInSession("AAPL", noon)).describedAs(day).isFalse()
            assertThat(cal.isClosedSession("AAPL", cal.sessionRange("AAPL", noon))).describedAs(day).isTrue()
        }
    }

    @Test
    fun `the previous day skips a special closure`() {
        val anchor = cal.anchorEpochFor(SessionAnchor.PreviousDay, Instant.parse("2025-01-10T15:00:00Z"))
        val range = cal.rangeFor(SessionAnchor.PreviousDay, anchor)
        assertThat(range.from).isEqualTo(Instant.parse("2025-01-08T14:30:00Z"))
        assertThat(range.to).isEqualTo(Instant.parse("2025-01-08T21:00:00Z"))
    }

    @Test
    fun `a daily window counts the average trading days of 2021 to 2024`() {
        // 252 + 251 + 250 + 252 sessions.
        assertThat(cal.tradingPeriodsPerYear(TimeWindow.ONE_DAY)).isEqualByComparingTo("251.25")
    }

    @Test
    fun `the yearly count equals a minute by minute scan of the session`() {
        val from =
            TradingPeriods.REFERENCE_FROM
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val to =
            TradingPeriods.REFERENCE_TO
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val openMinutes = ArrayList<Long>()
        var t = from
        while (t < to) {
            if (cal.isInSession("AAPL", Instant.ofEpochMilli(t))) openMinutes.add(t)
            t += 60_000L
        }
        for (spec in listOf("1m", "15m", "1h", "4h", "1d", "7d")) {
            val window = TimeWindow.parse(spec)
            val bars = openMinutes.map { Math.floorDiv(it, window.durationMs) }.toSet()
            assertThat(cal.tradingPeriodsPerYear(window).multiply(4.toBigDecimal()))
                .describedAs(spec)
                .isEqualByComparingTo(bars.size.toBigDecimal())
        }
    }

    @Test
    fun `a regular session touches seven hourly bars in summer and in winter`() {
        val hour = TimeWindow.ONE_HOUR.durationMs
        for (day in listOf("2024-06-12", "2024-01-10")) {
            val s = cal.sessionRange("AAPL", Instant.parse("${day}T16:00:00Z"))
            val bars = Math.floorDiv(s.to.toEpochMilli() - 1, hour) - Math.floorDiv(s.from.toEpochMilli(), hour) + 1
            assertThat(bars).describedAs(day).isEqualTo(7)
        }
    }
}
