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
    fun `the week closes at 17 New York, 21 UTC in summer and 22 UTC in winter`() {
        // FX trades on New York time. The summer close is 21:00 UTC (EDT, UTC-4): a hg20k Friday on the
        // fixed 22:00 rule raised STALE at 21:10 UTC on 2026-10-09 and held it all weekend, and the EURUSD
        // store's last summer Friday tick is 20:59:59 UTC.
        assertThat(cal.isInSession("EURUSD", Instant.parse("2026-10-09T20:59:00Z"))).isTrue()
        assertThat(cal.isInSession("EURUSD", Instant.parse("2026-10-09T21:00:00Z"))).isFalse()
        assertThat(cal.isInSession("EURUSD", Instant.parse("2026-01-16T21:59:00Z"))).isTrue()
        assertThat(cal.isInSession("EURUSD", Instant.parse("2026-01-16T22:00:00Z"))).isFalse()
    }

    @Test
    fun `the week opens at 17 New York on Sunday in either season`() {
        assertThat(cal.isInSession("EURUSD", Instant.parse("2026-10-11T20:59:00Z"))).isFalse()
        assertThat(cal.isInSession("EURUSD", Instant.parse("2026-10-11T21:00:00Z"))).isTrue()
        assertThat(cal.isInSession("EURUSD", Instant.parse("2026-01-18T21:59:00Z"))).isFalse()
        assertThat(cal.isInSession("EURUSD", Instant.parse("2026-01-18T22:00:00Z"))).isTrue()
    }

    @Test
    fun `a summer FX day runs from 21 UTC to 21 UTC`() {
        val r = cal.sessionRange("EURUSD", Instant.parse("2026-10-14T10:00:00Z"))
        assertThat(r.from).isEqualTo(Instant.parse("2026-10-13T21:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2026-10-14T21:00:00Z"))
    }

    @Test
    fun `the day across the daylight saving change is 25 hours and both ends sit on 17 New York`() {
        // DST ends Sunday 2026-11-01 02:00 New York: the previous day of Monday the 2nd opens Saturday 17:00 EDT.
        val mon = Instant.parse("2026-11-02T10:00:00Z")
        val current = cal.rangeFor(SessionAnchor.CurrentSession, cal.anchorEpochFor(SessionAnchor.CurrentSession, mon))
        assertThat(current.from).isEqualTo(Instant.parse("2026-11-01T22:00:00Z"))
        assertThat(current.to).isEqualTo(Instant.parse("2026-11-02T22:00:00Z"))
        val previous = cal.rangeFor(SessionAnchor.PreviousDay, cal.anchorEpochFor(SessionAnchor.PreviousDay, mon))
        assertThat(previous.from).isEqualTo(Instant.parse("2026-10-31T21:00:00Z"))
        assertThat(previous.to).isEqualTo(Instant.parse("2026-11-01T22:00:00Z"))
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
