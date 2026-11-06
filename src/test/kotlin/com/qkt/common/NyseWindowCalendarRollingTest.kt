package com.qkt.common

import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NyseWindowCalendarRollingTest {
    private val cal = TradingCalendar.named("nyse[06:00-15:45]") ?: error("windowed nyse calendar not known")

    @Test
    fun `a rolling anchor is the instant itself and its range trails it by the duration`() {
        val t = Instant.parse("2026-10-26T15:00:00Z")
        val rolling = SessionAnchor.Rolling(Duration.ofHours(4))

        val epoch = cal.anchorEpochFor(rolling, t)

        assertThat(epoch).isEqualTo(t.toEpochMilli())
        assertThat(cal.rangeFor(rolling, epoch)).isEqualTo(TimeRange(Instant.parse("2026-10-26T11:00:00Z"), t))
    }
}
