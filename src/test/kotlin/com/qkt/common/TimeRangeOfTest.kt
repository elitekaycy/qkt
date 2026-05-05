package com.qkt.common

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TimeRangeOfTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")
    private val clock = FixedClock(time = now.toEpochMilli())
    private val cal = TradingCalendar.crypto()

    @Test
    fun `Now resolves to clock now`() {
        val r = TimeRange.of(TimeMark.RelativeToNow(Duration.ofMinutes(-1)), TimeMark.Now, clock, cal)
        assertThat(r.to).isEqualTo(now)
    }

    @Test
    fun `Absolute resolves to its instant`() {
        val abs = Instant.parse("2024-01-14T00:00:00Z")
        val r =
            TimeRange.of(
                TimeMark.Absolute(abs),
                TimeMark.Absolute(now),
                clock,
                cal,
            )
        assertThat(r.from).isEqualTo(abs)
        assertThat(r.to).isEqualTo(now)
    }

    @Test
    fun `AtSessionAnchor without timeOfDay resolves to anchor range bounds`() {
        val r =
            TimeRange.of(
                TimeMark.AtSessionAnchor(SessionAnchor.PreviousDay),
                TimeMark.AtSessionAnchor(SessionAnchor.CurrentSession),
                clock,
                cal,
            )
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-14T00:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
    }

    @Test
    fun `AtSessionAnchor with timeOfDay clamps to that time within the anchor range`() {
        val r =
            TimeRange.of(
                TimeMark.AtSessionAnchor(SessionAnchor.PreviousDay, LocalTime.of(10, 0)),
                TimeMark.AtSessionAnchor(SessionAnchor.PreviousDay, LocalTime.of(18, 0)),
                clock,
                cal,
            )
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-14T10:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-14T18:00:00Z"))
    }

    @Test
    fun `RelativeToNow with negative offset resolves to past`() {
        val r =
            TimeRange.of(
                TimeMark.RelativeToNow(Duration.ofHours(-3)),
                TimeMark.Now,
                clock,
                cal,
            )
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-15T12:00:00Z"))
        assertThat(r.to).isEqualTo(now)
    }

    @Test
    fun `inverted range throws`() {
        assertThatThrownBy {
            TimeRange.of(TimeMark.Now, TimeMark.RelativeToNow(Duration.ofHours(-1)), clock, cal)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `range to in the future throws look-ahead`() {
        assertThatThrownBy {
            TimeRange.of(
                TimeMark.RelativeToNow(Duration.ofHours(-1)),
                TimeMark.RelativeToNow(Duration.ofHours(1)),
                clock,
                cal,
            )
        }.hasMessageContaining("look-ahead")
    }
}
