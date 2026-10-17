package com.qkt.candles

import com.qkt.common.Money
import com.qkt.events.WarmupTickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CandleAggregatorLateTickTest : CandleAggregatorFixture() {
    @Test
    fun `late ticks cannot reopen a window already closed by heartbeat`() {
        val emitted = mutableListOf<Candle>()
        val agg = CandleAggregator.standalone(TimeWindow.ONE_MINUTE) { emitted.add(it) }
        agg.onTick(Tick("X", Money.of("100"), 1_000L))
        agg.flushClosed(60_000L)
        agg.onTick(Tick("X", Money.of("200"), 30_000L))
        agg.onTick(Tick("X", Money.of("101"), 61_000L))
        agg.flushClosed(120_000L)

        assertThat(emitted.map { it.startTime }).containsExactly(0L, 60_000L)
        assertThat(emitted.first().high).isEqualByComparingTo("100")
        assertThat(agg.droppedLateTicks).isEqualTo(1)
    }

    @Test
    fun `synthetic warmup reordering does not report live tick loss`() {
        val agg = aggregator()
        bus.publish(WarmupTickEvent(Tick("X", Money.of("100"), 61_000L)))
        bus.publish(WarmupTickEvent(Tick("X", Money.of("99"), 1_000L)))

        assertThat(agg.droppedLateTicks).isZero()

        publishTick("X", Money.of("98"), 2_000L)
        assertThat(agg.droppedLateTicks).isEqualTo(1L)
    }

    @Test
    fun `a live tick inside a window seeded from venue history is covered, not dropped`() {
        // Seen at the v0.57.1 attestation: warmup at 14:13:00.7 closed the 14:12 bar from the broker's own
        // history, then the feed replayed its 14:12:59.970 tick. That tick is the broker bar's close: the bar
        // already holds it, so it is neither applied again nor reported as a bar that under-reports the venue.
        val emitted = mutableListOf<Candle>()
        val agg = CandleAggregator.standalone(TimeWindow.ONE_MINUTE) { emitted.add(it) }
        agg.closedThrough("X", 60_000L)
        agg.onTick(Tick("X", Money.of("1.12547"), 59_970L))

        assertThat(agg.droppedLateTicks).isZero()
        assertThat(agg.historyCoveredTicks).isEqualTo(1L)

        agg.onTick(Tick("X", Money.of("1.12545"), 60_227L))
        agg.flushClosed(120_000L)
        assertThat(emitted.single().startTime).isEqualTo(60_000L)
        assertThat(emitted.single().open).isEqualByComparingTo("1.12545")
    }

    @Test
    fun `a late tick for a bar the engine closed itself still counts as dropped`() {
        val agg = CandleAggregator.standalone(TimeWindow.ONE_MINUTE) {}
        agg.closedThrough("X", 60_000L)
        agg.onTick(Tick("X", Money.of("100"), 61_000L))
        agg.flushClosed(120_000L)
        agg.onTick(Tick("X", Money.of("101"), 119_000L))

        assertThat(agg.droppedLateTicks).isEqualTo(1L)
        assertThat(agg.historyCoveredTicks).isZero()
    }
}
