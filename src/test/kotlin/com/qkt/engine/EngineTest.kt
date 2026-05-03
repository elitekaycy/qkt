package com.qkt.engine

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.TickEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EngineTest {
    private val clock = FixedClock(time = 1000L)
    private val sequencer = MonotonicSequenceGenerator()
    private val tracker = MarketPriceTracker()
    private val bus = EventBus(clock, sequencer)
    private val engine = Engine(bus, tracker)

    @Test
    fun `onTick updates priceTracker before publishing TickEvent`() {
        val seenPrices = mutableListOf<Double?>()
        bus.subscribe<TickEvent> { seenPrices.add(tracker.lastPrice("XAUUSD")) }

        engine.onTick(Tick("XAUUSD", 2400.5, 999L))

        assertThat(seenPrices).containsExactly(2400.5)
    }

    @Test
    fun `onTick publishes TickEvent carrying the same Tick`() {
        val received = mutableListOf<Tick>()
        bus.subscribe<TickEvent> { received.add(it.tick) }

        val tick = Tick("XAUUSD", 2400.0, 999L)
        engine.onTick(tick)

        assertThat(received).containsExactly(tick)
    }
}
