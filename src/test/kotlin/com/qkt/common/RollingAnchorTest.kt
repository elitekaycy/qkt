package com.qkt.common

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RollingAnchorTest {
    @Test
    fun `a rolling anchor on the globex calendar trails the instant by its duration`() {
        val t = Instant.parse("2026-10-27T15:00:00Z")
        val rolling = SessionAnchor.Rolling(Duration.ofMinutes(90))

        val epoch = CmeGlobexCalendar.anchorEpochFor(rolling, t)

        assertThat(epoch).isEqualTo(t.toEpochMilli())
        assertThat(
            CmeGlobexCalendar.rangeFor(rolling, epoch),
        ).isEqualTo(TimeRange(Instant.parse("2026-10-27T13:30:00Z"), t))
    }

    @Test
    fun `a rolling anchor needs a positive duration`() {
        assertThatThrownBy {
            SessionAnchor.Rolling(
                Duration.ZERO,
            )
        }.hasMessageContaining("Rolling duration must be positive")
        assertThatThrownBy {
            SessionAnchor.Rolling(Duration.ofSeconds(-5))
        }.hasMessageContaining("Rolling duration must be positive")
    }

    @Test
    fun `a calendar that cannot count periods says so by name`() {
        val bare =
            object : TradingCalendar by TradingCalendar.crypto() {
                override val name = "bare"

                override fun tradingPeriodsPerYear(window: TimeWindow): BigDecimal = super.tradingPeriodsPerYear(window)
            }

        assertThatThrownBy { bare.tradingPeriodsPerYear(TimeWindow.ONE_HOUR) }
            .hasMessageContaining("tradingPeriodsPerYear not implemented for bare")
    }
}
