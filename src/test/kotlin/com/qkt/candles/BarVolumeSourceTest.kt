package com.qkt.candles

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A polled tick feed under-counts ticks, so where the venue publishes its own figure the
 * bar must carry that instead. These pin the substitution and, just as importantly, that a
 * missing or failing source leaves the aggregated count untouched.
 */
class BarVolumeSourceTest {
    private fun ticks(n: Int) = (1..n).map { Tick("X", Money.of("100"), it * 1_000L) }

    private fun aggregate(
        source: BarVolumeSource?,
        n: Int = 5,
    ): List<Candle> {
        val out = mutableListOf<Candle>()
        val agg = CandleAggregator.standalone(TimeWindow.ONE_MINUTE, source) { out.add(it) }
        ticks(n).forEach(agg::onTick)
        agg.flushClosed(Long.MAX_VALUE)
        return out
    }

    @Test
    fun `venue volume replaces the polled tick count`() {
        val venue = BarVolumeSource { _, _, _ -> Money.of("137") }
        assertThat(aggregate(venue).first().volume).isEqualByComparingTo(BigDecimal("137"))
    }

    @Test
    fun `no source leaves the counted volume alone`() {
        // five ticks, none carrying size: the count is the only figure available
        assertThat(aggregate(null).first().volume).isEqualByComparingTo(BigDecimal("5"))
    }

    @Test
    fun `a source that has not fetched the bar yet degrades to the count`() {
        val empty = BarVolumeSource { _, _, _ -> null }
        assertThat(aggregate(empty).first().volume).isEqualByComparingTo(BigDecimal("5"))
    }
}
