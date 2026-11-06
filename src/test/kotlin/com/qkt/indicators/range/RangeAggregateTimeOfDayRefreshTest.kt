package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.RefreshTrigger
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An `OnTimeOfDay` refresh recomputes the range once each time the UTC clock passes the configured time. */
class RangeAggregateTimeOfDayRefreshTest {
    private val clock = FixedClock(time = Instant.parse("2024-01-15T07:00:00Z").toEpochMilli())
    private var reads = 0

    private val source =
        object : MarketSource {
            override val name = "Fake"
            override val capabilities = setOf(MarketSourceCapability.BARS)

            override fun supports(symbol: String): Boolean = true

            override fun bars(
                symbol: String,
                window: TimeWindow,
                range: TimeRange,
            ): Sequence<Candle> {
                reads++
                val p = Money.of(reads.toString())
                return sequenceOf(Candle("X", p, p, p, p, Money.ZERO, 0L, 60_000L))
            }
        }

    private val indicator =
        RangeAggregateIndicator(
            symbol = "X",
            window = TimeWindow.ONE_MINUTE,
            rangeSpec = { TimeRange(Instant.EPOCH, Instant.EPOCH.plusSeconds(60)) },
            reduce = { bars -> bars.maxOfOrNull { it.high } },
            source = source,
            clock = clock,
            refreshOn = RefreshTrigger.OnTimeOfDay(LocalTime.of(8, 0)),
        )

    private fun at(iso: String) {
        clock.time = Instant.parse(iso).toEpochMilli()
        indicator.update(Tick("X", Money.of("1"), clock.time))
    }

    @Test
    fun `the range is recomputed when 08 00 UTC passes and held until the next day's`() {
        at("2024-01-15T07:00:00Z")
        at("2024-01-15T07:59:00Z")
        assertThat(reads).isEqualTo(1)

        at("2024-01-15T08:00:00Z")
        at("2024-01-16T07:30:00Z")
        assertThat(reads).isEqualTo(2)
        assertThat(indicator.value()).isEqualByComparingTo(Money.of("2"))

        at("2024-01-16T08:01:00Z")
        assertThat(reads).isEqualTo(3)
    }

    @Test
    fun `ticks for another symbol leave the range untouched`() {
        indicator.update(Tick("Y", Money.of("1"), clock.time))

        assertThat(reads).isZero
        assertThat(indicator.isReady).isFalse
    }
}
