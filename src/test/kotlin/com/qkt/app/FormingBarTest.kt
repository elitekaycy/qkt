package com.qkt.app

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A session that starts part-way through a higher-timeframe window (#1196). */
class FormingBarTest {
    private val hour = TimeWindow.ONE_HOUR
    private val minute = 60_000L

    /** One-minute bars for 00:00..01:00: the high of the hour is at minute 5, the low at minute 50. */
    private fun minuteBar(index: Int): Candle {
        val open = BigDecimal(100 + index)
        val high = if (index == 5) BigDecimal(500) else open.add(BigDecimal.ONE)
        val low = if (index == 50) BigDecimal(1) else open.subtract(BigDecimal.ONE)
        return Candle("X", open, high, low, open, BigDecimal.TEN, index * minute, (index + 1) * minute)
    }

    private val source =
        object : MarketSource {
            override val name = "minutes"
            override val capabilities = setOf(MarketSourceCapability.BARS)

            override fun supports(symbol: String) = true

            override fun bars(
                symbol: String,
                window: TimeWindow,
                range: TimeRange,
            ): Sequence<Candle> {
                require(window == TimeWindow.ONE_MINUTE)
                return (0 until 60).map(::minuteBar).asSequence().filter {
                    it.startTime >= range.from.toEpochMilli() && it.startTime < range.to.toEpochMilli()
                }
            }
        }

    @Test
    fun `the elapsed part of the window folds into one partial candle, using only whole minutes before now`() {
        val startedAt = 37 * minute + 12_000L

        val forming = checkNotNull(FormingBar.load(source, "X", hour, startedAt))

        assertThat(forming.minutes).hasSize(37)
        assertThat(forming.partial.startTime).isZero()
        assertThat(forming.partial.endTime).isEqualTo(hour.durationMs)
        assertThat(forming.partial.open).isEqualByComparingTo("100")
        assertThat(forming.partial.high).isEqualByComparingTo("500")
        assertThat(forming.partial.low).isEqualByComparingTo("99")
        assertThat(forming.partial.close).isEqualByComparingTo("136")
    }

    @Test
    fun `nothing is seeded for an aligned start or a one-minute stream`() {
        assertThat(FormingBar.load(source, "X", hour, hour.durationMs)).isNull()
        assertThat(FormingBar.load(source, "X", hour, 40_000L)).isNull()
        assertThat(FormingBar.load(source, "X", TimeWindow.ONE_MINUTE, 37 * minute)).isNull()
    }

    @Test
    fun `a bar seeded mid-window closes with the open, high and low of the whole window`() {
        val closed = mutableListOf<Candle>()
        val aggregator = CandleAggregator.standalone(hour) { closed.add(it) }
        val startedAt = 37 * minute
        aggregator.seedForming(checkNotNull(FormingBar.load(source, "X", hour, startedAt)).partial)

        // The live ticks after the start: they never revisit the early high, and they make the low.
        for (index in 37 until 60) {
            val bar = minuteBar(index)
            aggregator.onTick(Tick("X", bar.low, bar.startTime + 1))
            aggregator.onTick(Tick("X", bar.close, bar.endTime - 1))
        }
        aggregator.flushClosed(hour.durationMs)

        val bar = closed.single()
        assertThat(bar.open).isEqualByComparingTo("100")
        assertThat(bar.high).isEqualByComparingTo("500")
        assertThat(bar.low).isEqualByComparingTo("1")
        assertThat(bar.close).isEqualByComparingTo("159")
    }

    @Test
    fun `seeding never replaces a candle the aggregator is already building`() {
        val closed = mutableListOf<Candle>()
        val aggregator = CandleAggregator.standalone(hour) { closed.add(it) }
        aggregator.onTick(Tick("X", BigDecimal(42), 10 * minute))

        aggregator.seedForming(checkNotNull(FormingBar.load(source, "X", hour, 37 * minute)).partial)
        aggregator.flushClosed(hour.durationMs)

        assertThat(closed.single().open).isEqualByComparingTo("42")
    }
}
