package com.qkt.common

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NyseCalendarTest {
    private val cal = TradingCalendar.nyse()

    @Test
    fun `name is nyse`() {
        assertThat(cal.name).isEqualTo("nyse")
    }

    @Test
    fun `weekday in session window is open`() {
        val t = Instant.parse("2024-01-16T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isTrue()
    }

    @Test
    fun `weekday before 9_30 ET is closed`() {
        val t = Instant.parse("2024-01-16T13:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `weekday after 16_00 ET is closed`() {
        val t = Instant.parse("2024-01-16T22:30:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `saturday is closed`() {
        val t = Instant.parse("2024-01-13T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `MLK day 2024 is closed`() {
        val t = Instant.parse("2024-01-15T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `christmas day 2024 is closed`() {
        val t = Instant.parse("2024-12-25T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `independence day 2024 is closed`() {
        val t = Instant.parse("2024-07-04T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `previous day anchor returns previous trading day`() {
        val mon = Instant.parse("2024-01-16T15:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.PreviousDay, mon)
        val range = cal.rangeFor(SessionAnchor.PreviousDay, key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-12T14:30:00Z"))
        assertThat(range.to).isEqualTo(Instant.parse("2024-01-12T21:00:00Z"))
    }
}
