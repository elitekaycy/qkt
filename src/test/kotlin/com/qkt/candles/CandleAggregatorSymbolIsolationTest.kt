package com.qkt.candles

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CandleAggregatorSymbolIsolationTest : CandleAggregatorFixture() {
    @Test
    fun `windows for different symbols are tracked independently`() {
        aggregator()
        publishTick("XAUUSD", Money.of("2400.0"), 0L)
        publishTick("EURUSD", Money.of("1.0921"), 30_000L)
        publishTick("XAUUSD", Money.of("2401.0"), 75_000L)
        publishTick("EURUSD", Money.of("1.0930"), 75_000L)
        assertThat(captured).hasSize(2)
        assertThat(captured.map { it.candle.symbol }).containsExactly("XAUUSD", "EURUSD")
    }

    @Test
    fun `aggregates per symbol with interleaved input`() {
        val bus = EventBus(FixedClock(time = 0L), MonotonicSequenceGenerator())
        CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        val emitted = mutableListOf<Candle>()
        bus.subscribe<CandleEvent> { emitted.add(it.candle) }

        bus.publish(TickEvent(Tick("A", Money.of("100"), 0L)))
        bus.publish(TickEvent(Tick("B", Money.of("200"), 1_000L)))
        bus.publish(TickEvent(Tick("A", Money.of("110"), 30_000L)))
        bus.publish(TickEvent(Tick("B", Money.of("190"), 45_000L)))
        bus.publish(TickEvent(Tick("A", Money.of("120"), 61_000L)))
        bus.publish(TickEvent(Tick("B", Money.of("210"), 62_000L)))

        assertThat(emitted).hasSize(2)
        val candleA = emitted.first { it.symbol == "A" }
        val candleB = emitted.first { it.symbol == "B" }
        assertThat(candleA.high).isEqualByComparingTo(Money.of("110"))
        assertThat(candleB.high).isEqualByComparingTo(Money.of("200"))
        assertThat(candleB.low).isEqualByComparingTo(Money.of("190"))
    }

    @Test
    fun `each symbol's OHLC is independent`() {
        val bus = EventBus(FixedClock(time = 0L), MonotonicSequenceGenerator())
        CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        val emitted = mutableListOf<Candle>()
        bus.subscribe<CandleEvent> { emitted.add(it.candle) }

        bus.publish(TickEvent(Tick("A", Money.of("100"), 0L)))
        bus.publish(TickEvent(Tick("A", Money.of("9999"), 5_000L)))
        bus.publish(TickEvent(Tick("B", Money.of("50"), 10_000L)))
        bus.publish(TickEvent(Tick("B", Money.of("51"), 20_000L)))
        bus.publish(TickEvent(Tick("A", Money.of("100"), 61_000L)))
        bus.publish(TickEvent(Tick("B", Money.of("52"), 62_000L)))

        val candleB = emitted.first { it.symbol == "B" }
        assertThat(candleB.high).isEqualByComparingTo(Money.of("51"))
        assertThat(candleB.low).isEqualByComparingTo(Money.of("50"))
    }

    @Test
    fun `unknown symbol on first sight starts a fresh in progress candle`() {
        val bus = EventBus(FixedClock(time = 0L), MonotonicSequenceGenerator())
        CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        val emitted = mutableListOf<Candle>()
        bus.subscribe<CandleEvent> { emitted.add(it.candle) }

        bus.publish(TickEvent(Tick("A", Money.of("100"), 0L)))
        bus.publish(TickEvent(Tick("NEW", Money.of("999"), 1_000L)))
        bus.publish(TickEvent(Tick("A", Money.of("101"), 61_000L)))
        bus.publish(TickEvent(Tick("NEW", Money.of("1000"), 62_000L)))

        assertThat(emitted.map { it.symbol }).containsExactlyInAnyOrder("A", "NEW")
    }
}
