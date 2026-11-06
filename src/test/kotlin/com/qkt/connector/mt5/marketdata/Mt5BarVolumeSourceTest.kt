package com.qkt.connector.mt5.marketdata

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.connector.mt5.Mt5RoutedGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The venue's tick volume for recent one-minute bars, kept for a rolling window of two bars. */
class Mt5BarVolumeSourceTest {
    private val minute = 60_000L
    private val t0 = 1_800_000_000_000L / minute * minute
    private val gateway = Mt5RoutedGateway()
    private val clock = FixedClock(time = t0)
    private val source = Mt5BarVolumeSource(Mt5BarFetcher(gateway.url), TimeWindow(minute), clock, retainBars = 2)

    @AfterEach
    fun stop() = gateway.close()

    /** One bar a minute from t0-5m to t0+15m, each with volume equal to its minute offset plus 100. */
    private fun venueHistory() =
        gateway.on(
            "/fetch_data_range",
            (-5..15).joinToString(",", "[", "]") { i ->
                """{"open":1,"high":1,"low":1,"close":1,"tick_volume":${100 + i},"time":${(t0 + i * minute) / 1000}}"""
            },
        )

    private fun volumeAt(offset: Int) = source.volumeFor("XAUUSDm", t0 + offset * minute, t0 + (offset + 1) * minute)

    @Test
    fun `a refresh remembers the venue volume of the bars closed inside its window`() {
        venueHistory()

        source.refresh("XAUUSDm")

        assertThat(volumeAt(-2)).isEqualByComparingTo("98")
        assertThat(volumeAt(-1)).isEqualByComparingTo("99")
        assertThat(volumeAt(-3)).isNull()
        assertThat(volumeAt(0)).isNull()
        assertThat(source.volumeFor("EURUSDm", t0 - minute, t0)).isNull()
        assertThat(gateway.calls("/fetch_data_range").single()).contains("symbol=XAUUSDm&timeframe=M1")
    }

    @Test
    fun `a failing history endpoint leaves the aggregated count in charge`() {
        gateway.fail("/fetch_data_range")

        source.refresh("XAUUSDm")

        assertThat(volumeAt(-1)).isNull()
    }

    @Test
    fun `old bars are dropped once the store outgrows four windows`() {
        venueHistory()

        for (step in 0..4) {
            clock.time = t0 + step * 2 * minute
            source.refresh("XAUUSDm")
        }

        // Five refreshes stored ten bars, more than 2 x 4: bars older than two windows back are dropped.
        assertThat(volumeAt(-2)).isNull()
        assertThat(volumeAt(3)).isNull()
        assertThat(volumeAt(4)).isEqualByComparingTo("104")
        assertThat(volumeAt(7)).isEqualByComparingTo("107")
    }

    @Test
    fun `an empty answer stores nothing and prunes nothing`() {
        venueHistory()
        source.refresh("XAUUSDm")
        gateway.on("/fetch_data_range", "[]")

        clock.time = t0 + 10 * minute
        source.refresh("XAUUSDm")

        assertThat(volumeAt(-1)).isEqualByComparingTo("99")
    }
}
