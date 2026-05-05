package com.qkt.common

import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FxCalendarTest {
    private val cal = TradingCalendar.fxDefault()

    @Test
    fun `name is fx`() {
        assertThat(cal.name).isEqualTo("fx")
    }

    @Test
    fun `weekday afternoon UTC is in session`() {
        val mon = Instant.parse("2024-01-15T15:00:00Z")
        assertThat(cal.isInSession("EURUSD", mon)).isTrue()
    }

    @Test
    fun `saturday morning is closed`() {
        val sat = Instant.parse("2024-01-13T08:00:00Z")
        assertThat(cal.isInSession("EURUSD", sat)).isFalse()
    }

    @Test
    fun `friday before 22 UTC is in session`() {
        val fri = Instant.parse("2024-01-12T21:59:00Z")
        assertThat(cal.isInSession("EURUSD", fri)).isTrue()
    }

    @Test
    fun `friday at 22 UTC is closed`() {
        val fri = Instant.parse("2024-01-12T22:00:00Z")
        assertThat(cal.isInSession("EURUSD", fri)).isFalse()
    }

    @Test
    fun `sunday at 22 UTC reopens`() {
        val sun = Instant.parse("2024-01-14T22:00:00Z")
        assertThat(cal.isInSession("EURUSD", sun)).isTrue()
    }

    @Test
    fun `sessionRange for weekday returns the FX day from 22 UTC of previous calendar day to 22 UTC of t`() {
        val mon = Instant.parse("2024-01-15T10:00:00Z")
        val r = cal.sessionRange("EURUSD", mon)
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-14T22:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-15T22:00:00Z"))
    }

    @Test
    fun `previous day anchor on monday returns sunday-night-into-monday FX day`() {
        val mon = Instant.parse("2024-01-15T10:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.PreviousDay, mon)
        val range = cal.rangeFor(SessionAnchor.PreviousDay, key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-13T22:00:00Z"))
        assertThat(range.to).isEqualTo(Instant.parse("2024-01-14T22:00:00Z"))
    }

    @Test
    fun `current session anchor on monday returns the in-progress monday FX day`() {
        val mon = Instant.parse("2024-01-15T10:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.CurrentSession, mon)
        val range = cal.rangeFor(SessionAnchor.CurrentSession, key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-14T22:00:00Z"))
        assertThat(range.to).isEqualTo(Instant.parse("2024-01-15T22:00:00Z"))
    }

    @Test
    fun `rolling anchor independent of session boundaries`() {
        val mon = Instant.parse("2024-01-15T10:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.Rolling(Duration.ofHours(2)), mon)
        val range = cal.rangeFor(SessionAnchor.Rolling(Duration.ofHours(2)), key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-15T08:00:00Z"))
        assertThat(range.to).isEqualTo(mon)
    }
}
