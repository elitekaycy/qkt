package com.qkt.common

import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CryptoCalendarTest {
    private val cal = TradingCalendar.crypto()
    private val midnight = Instant.parse("2024-01-15T00:00:00Z")

    @Test
    fun `name is crypto`() {
        assertThat(cal.name).isEqualTo("crypto")
    }

    @Test
    fun `is in session at any time`() {
        assertThat(cal.isInSession("BTCUSD", midnight)).isTrue()
        assertThat(cal.isInSession("BTCUSD", midnight.plus(Duration.ofHours(3)))).isTrue()
        assertThat(cal.isInSession("BTCUSD", midnight.plus(Duration.ofDays(7)))).isTrue()
    }

    @Test
    fun `sessionRange for crypto is the calendar UTC day containing t`() {
        val r = cal.sessionRange("BTCUSD", midnight.plus(Duration.ofHours(3)))
        assertThat(r.from).isEqualTo(midnight)
        assertThat(r.to).isEqualTo(midnight.plus(Duration.ofDays(1)))
    }

    @Test
    fun `previous day anchor returns yesterday epoch`() {
        val today = Instant.parse("2024-01-15T15:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.PreviousDay, today)
        val range = cal.rangeFor(SessionAnchor.PreviousDay, key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-14T00:00:00Z"))
        assertThat(range.to).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
    }

    @Test
    fun `current session anchor returns today epoch`() {
        val today = Instant.parse("2024-01-15T15:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.CurrentSession, today)
        val range = cal.rangeFor(SessionAnchor.CurrentSession, key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
        assertThat(range.to).isEqualTo(Instant.parse("2024-01-16T00:00:00Z"))
    }

    @Test
    fun `rolling anchor returns the duration ending at t`() {
        val now = Instant.parse("2024-01-15T15:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.Rolling(Duration.ofHours(6)), now)
        val range = cal.rangeFor(SessionAnchor.Rolling(Duration.ofHours(6)), key)
        assertThat(range.to).isEqualTo(now)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-15T09:00:00Z"))
    }
}
