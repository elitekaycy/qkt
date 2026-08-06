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

    @Test
    fun `a net-short symbol sees High before Low so the adverse extreme arrives first`() {
        val feed = BarTickFeed(
            sequenceOf(candle("100", "110", "90", "105", 0L, 300_000L)),
            positionSign = { -1 },
        )
        val out = generateSequence { feed.next() }.toList()
        assertThat(out.map { it.price }).containsExactly(
            BigDecimal("100"),
            BigDecimal("110"),
            BigDecimal("90"),
            BigDecimal("105"),
        )
        assertThat(out.map { it.timestamp }).isSorted()
        assertThat(out.last().volume).isEqualByComparingTo("10")
    }

    @Test
    fun `flat and long symbols keep the Low-first default`() {
        for (sign in intArrayOf(0, 1)) {
            val feed = BarTickFeed(
                sequenceOf(candle("100", "110", "90", "105", 0L, 300_000L)),
                positionSign = { sign },
            )
            val out = generateSequence { feed.next() }.toList()
            assertThat(out.map { it.price }).containsExactly(
                BigDecimal("100"),
                BigDecimal("90"),
                BigDecimal("110"),
                BigDecimal("105"),
            )
        }
    }

    @Test
    fun `ordering is decided per bar after the Open tick is consumed`() {
        // Sign flips to short only once the first bar's Open has been seen — modeling a short
        // entry filled on this bar's open steering its own extremes.
        var sign = 0
        val feed = BarTickFeed(
            sequenceOf(
                candle("100", "110", "90", "105", 0L, 300_000L),
                candle("105", "108", "104", "107", 300_000L, 600_000L),
            ),
            positionSign = { sign },
        )
        val first = feed.next()!!
        assertThat(first.price).isEqualByComparingTo("100")
        sign = -1
        val rest = generateSequence { feed.next() }.toList()
        // Bar 1 extremes: adverse-first for the short opened on its open -> High then Low.
        assertThat(rest.map { it.price }.take(3)).containsExactly(
            BigDecimal("110"),
            BigDecimal("90"),
            BigDecimal("105"),
        )
        // Bar 2 still short -> High (108) before Low (104).
        assertThat(rest.map { it.price }.drop(3)).containsExactly(
            BigDecimal("105"),
            BigDecimal("108"),
            BigDecimal("104"),
            BigDecimal("107"),
        )
    }

    @Test
    fun `ordering decision is cached per bar so a mid-bar flatten cannot emit one extreme twice`() {
        // Short when the ordering is decided; the first extreme (High) fills the stop and the
        // position goes flat — the second extreme must still be the Low, not a repeated High.
        var sign = -1
        val feed = BarTickFeed(
            sequenceOf(candle("100", "110", "90", "105", 0L, 300_000L)),
            positionSign = { sign },
        )
        val open = feed.next()!!
        assertThat(open.price).isEqualByComparingTo("100")
        val ext1 = feed.next()!!
        assertThat(ext1.price).isEqualByComparingTo("110")
        sign = 0
        val ext2 = feed.next()!!
        assertThat(ext2.price).isEqualByComparingTo("90")
    }

    @Test
    fun `short-ordered ticks still re-aggregate to the original candle`() {
        val feed = BarTickFeed(
            sequenceOf(candle("100", "110", "90", "105", 0L, 300_000L)),
            positionSign = { -1 },
        )
        val emitted = mutableListOf<Candle>()
        val agg = CandleAggregator.standalone(TimeWindow.FIVE_MINUTES) { emitted.add(it) }
        generateSequence { feed.next() }.forEach { agg.onTick(it) }
        agg.onTick(Tick("BYBIT_SPOT:BTCUSDT", BigDecimal("107"), 300_000L))
        val rebuilt = emitted.single()
        assertThat(rebuilt.open).isEqualByComparingTo("100")
        assertThat(rebuilt.high).isEqualByComparingTo("110")
        assertThat(rebuilt.low).isEqualByComparingTo("90")
        assertThat(rebuilt.close).isEqualByComparingTo("105")
        assertThat(rebuilt.volume).isEqualByComparingTo("10")
    }
}
