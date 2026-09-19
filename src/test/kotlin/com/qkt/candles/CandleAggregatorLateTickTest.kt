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
}
