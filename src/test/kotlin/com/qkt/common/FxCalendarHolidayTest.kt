package com.qkt.common

import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FxCalendarHolidayTest {
    private val fx = TradingCalendar.fxDefault()

    private fun sessionAt(t: String) = fx.sessionRange("XAUUSD", Instant.parse(t))

    @Test
    fun `Good Friday follows Easter across years`() {
        val expected =
            mapOf(
                2019 to "2019-04-19",
                2020 to "2020-04-10",
                2021 to "2021-04-02",
                2022 to "2022-04-15",
                2023 to "2023-04-07",
                2024 to "2024-03-29",
                2025 to "2025-04-18",
                2026 to "2026-04-03",
                2038 to "2038-04-23",
            )
        expected.forEach { (year, day) -> assertThat(WesternEaster.goodFriday(year)).isEqualTo(LocalDate.parse(day)) }
    }

    @Test
    fun `the FX days closing on Christmas, New Year's Day and Good Friday are closed`() {
        // Each FX day ends 17:00 New York: the Good Friday 2024 day is 03-28 21:00 UTC to 03-29 21:00 UTC.
        assertThat(fx.isClosedSession("XAUUSD", sessionAt("2024-03-29T10:00:00Z"))).isTrue()
        assertThat(fx.isClosedSession("XAUUSD", sessionAt("2024-03-28T21:30:00Z"))).isTrue()
        assertThat(fx.isClosedSession("XAUUSD", sessionAt("2024-03-28T20:30:00Z"))).isFalse()
        assertThat(fx.isClosedSession("XAUUSD", sessionAt("2023-12-25T12:00:00Z"))).isTrue()
        assertThat(fx.isClosedSession("XAUUSD", sessionAt("2023-12-25T22:30:00Z"))).isFalse()
        assertThat(fx.isClosedSession("XAUUSD", sessionAt("2024-01-01T12:00:00Z"))).isTrue()
        assertThat(fx.isClosedSession("XAUUSD", sessionAt("2024-02-19T12:00:00Z"))).isFalse()
    }

    @Test
    fun `holidays leave live session gating on the plain 24x5 week`() {
        assertThat(fx.isInSession("XAUUSD", Instant.parse("2024-03-29T10:00:00Z"))).isTrue()
        assertThat(fx.isInSession("XAUUSD", Instant.parse("2023-12-25T12:00:00Z"))).isTrue()
    }

    @Test
    fun `crypto has no closures or short sessions and NYSE closes its holidays`() {
        val crypto = TradingCalendar.crypto()
        assertThat(
            crypto.isClosedSession("BTCUSD", crypto.sessionRange("BTCUSD", Instant.parse("2024-12-25T12:00:00Z"))),
        ).isFalse()
        assertThat(crypto.hasShortSessions).isFalse()
        val nyse = TradingCalendar.nyse()
        assertThat(
            nyse.isClosedSession("SPX", nyse.sessionRange("SPX", Instant.parse("2024-03-29T15:00:00Z"))),
        ).isTrue()
        assertThat(
            nyse.isClosedSession("SPX", nyse.sessionRange("SPX", Instant.parse("2024-03-28T15:00:00Z"))),
        ).isFalse()
    }
}
