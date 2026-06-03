package com.qkt.marketdata.source

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BarTickFeedTest {
    private fun candle(
        o: String,
        h: String,
        l: String,
        c: String,
        start: Long,
        end: Long,
    ) = Candle(
        symbol = "BYBIT_SPOT:BTCUSDT",
        open = BigDecimal(o),
        high = BigDecimal(h),
        low = BigDecimal(l),
        close = BigDecimal(c),
        volume = BigDecimal("10"),
        startTime = start,
        endTime = end,
    )

    @Test
    fun `candleToTicks emits O L H C in order with strictly increasing in-window timestamps`() {
        val ticks = candleToTicks(candle("100", "110", "90", "105", 0L, 300_000L))
        assertThat(ticks.map { it.price }).containsExactly(
            BigDecimal("100"),
            BigDecimal("90"),
            BigDecimal("110"),
            BigDecimal("105"),
        )
        assertThat(ticks.map { it.timestamp }).isSorted()
        assertThat(ticks.first().timestamp).isGreaterThanOrEqualTo(0L)
        assertThat(ticks.last().timestamp).isLessThan(300_000L)
        assertThat(ticks.map { it.volume ?: BigDecimal.ZERO }.reduce(BigDecimal::add))
            .isEqualByComparingTo("10")
    }

    @Test
    fun `the four ticks re-aggregate to the original candle`() {
        val cdl = candle("100", "110", "90", "105", 0L, 300_000L)
        val emitted = mutableListOf<Candle>()
        val agg = CandleAggregator.standalone(TimeWindow.FIVE_MINUTES) { emitted.add(it) }
        for (t in candleToTicks(cdl)) agg.onTick(t)
        // A tick in the next window forces the current candle to close.
        agg.onTick(Tick("BYBIT_SPOT:BTCUSDT", BigDecimal("107"), 300_000L))
        val rebuilt = emitted.single()
        assertThat(rebuilt.open).isEqualByComparingTo("100")
        assertThat(rebuilt.high).isEqualByComparingTo("110")
        assertThat(rebuilt.low).isEqualByComparingTo("90")
        assertThat(rebuilt.close).isEqualByComparingTo("105")
        assertThat(rebuilt.volume).isEqualByComparingTo("10")
        assertThat(rebuilt.startTime).isEqualTo(0L)
        assertThat(rebuilt.endTime).isEqualTo(300_000L)
    }

    @Test
    fun `BarTickFeed flattens candles into ticks in chronological order`() {
        val feed =
            BarTickFeed(
                sequenceOf(
                    candle("100", "110", "90", "105", 0L, 300_000L),
                    candle("105", "108", "104", "107", 300_000L, 600_000L),
                ),
            )
        val out = generateSequence { feed.next() }.toList()
        assertThat(out).hasSize(8)
        assertThat(out.map { it.timestamp }).isSorted()
    }
}
