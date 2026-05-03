package com.qkt.bus

import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.CandleEvent
import com.qkt.events.Event
import com.qkt.events.OrderEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EventBusTest {
    private val clock = FixedClock(time = 1000L)
    private val sequencer = MonotonicSequenceGenerator()

    private fun newBus() = EventBus(clock, sequencer)

    private fun tick(
        symbol: String = "XAUUSD",
        price: Double = 2400.0,
    ) = Tick(symbol, price, 999L)

    @Test
    fun `publish with no subscribers is a no-op`() {
        val bus = newBus()
        bus.publish(TickEvent(tick()))
    }

    @Test
    fun `subscribe then publish invokes the handler with the event`() {
        val bus = newBus()
        val received = mutableListOf<TickEvent>()
        bus.subscribe<TickEvent> { received.add(it) }

        val event = TickEvent(tick("XAUUSD", 2400.5))
        bus.publish(event)

        assertThat(received).hasSize(1)
        assertThat(received[0].tick).isEqualTo(event.tick)
    }

    @Test
    fun `bus stamps timestamp from clock on publish`() {
        val bus = newBus()
        val received = mutableListOf<TickEvent>()
        bus.subscribe<TickEvent> { received.add(it) }

        clock.time = 12345L
        bus.publish(TickEvent(tick()))

        assertThat(received[0].timestamp).isEqualTo(12345L)
    }

    @Test
    fun `bus stamps CandleEvent on publish`() {
        val bus = newBus()
        val received = mutableListOf<CandleEvent>()
        bus.subscribe<CandleEvent> { received.add(it) }

        clock.time = 7777L
        bus.publish(
            CandleEvent(
                Candle(
                    "XAUUSD",
                    open = 100.0,
                    high = 101.0,
                    low = 99.0,
                    close = 100.5,
                    volume = 12.0,
                    startTime = 0L,
                    endTime = 60_000L,
                ),
            ),
        )

        assertThat(received).hasSize(1)
        assertThat(received[0].timestamp).isEqualTo(7777L)
        assertThat(received[0].sequenceId).isEqualTo(0L)
        assertThat(received[0].candle.symbol).isEqualTo("XAUUSD")
    }

    @Test
    fun `bus stamps monotonic sequenceId on publish`() {
        val bus = newBus()
        val received = mutableListOf<Event>()
        bus.subscribe<TickEvent> { received.add(it) }
        bus.subscribe<SignalEvent> { received.add(it) }

        bus.publish(TickEvent(tick()))
        bus.publish(SignalEvent(Signal.Buy("XAUUSD", 1.0)))
        bus.publish(TickEvent(tick()))

        assertThat(received.map { it.sequenceId }).containsExactly(0L, 1L, 2L)
    }

    @Test
    fun `multiple subscribers to same event run in registration order`() {
        val bus = newBus()
        val order = mutableListOf<String>()
        bus.subscribe<TickEvent> { order.add("first") }
        bus.subscribe<TickEvent> { order.add("second") }
        bus.subscribe<TickEvent> { order.add("third") }

        bus.publish(TickEvent(tick()))

        assertThat(order).containsExactly("first", "second", "third")
    }

    @Test
    fun `subscribers to different event types are isolated`() {
        val bus = newBus()
        val ticks = mutableListOf<TickEvent>()
        val signals = mutableListOf<SignalEvent>()
        bus.subscribe<TickEvent> { ticks.add(it) }
        bus.subscribe<SignalEvent> { signals.add(it) }

        bus.publish(TickEvent(tick()))

        assertThat(ticks).hasSize(1)
        assertThat(signals).isEmpty()
    }

    @Test
    fun `subscriber publishing different event type runs depth-first`() {
        val bus = newBus()
        val sequence = mutableListOf<String>()
        bus.subscribe<TickEvent> {
            sequence.add("tick-A-start")
            bus.publish(SignalEvent(Signal.Buy("XAUUSD", 1.0)))
            sequence.add("tick-A-end")
        }
        bus.subscribe<TickEvent> { sequence.add("tick-B") }
        bus.subscribe<SignalEvent> { sequence.add("signal-handler") }

        bus.publish(TickEvent(tick()))

        assertThat(sequence).containsExactly("tick-A-start", "signal-handler", "tick-A-end", "tick-B")
    }

    @Test
    fun `subscriber exception propagates out of publish`() {
        val bus = newBus()
        bus.subscribe<TickEvent> { error("boom") }

        assertThatThrownBy { bus.publish(TickEvent(tick())) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("boom")
    }

    @Test
    fun `published event default timestamp and sequenceId are overwritten`() {
        val bus = newBus()
        val received = mutableListOf<TickEvent>()
        bus.subscribe<TickEvent> { received.add(it) }

        clock.time = 5555L
        bus.publish(TickEvent(tick(), timestamp = 999L, sequenceId = 999L))

        assertThat(received[0].timestamp).isEqualTo(5555L)
        assertThat(received[0].sequenceId).isEqualTo(0L)
    }

    @Test
    fun `each event type retains its own subscriber list`() {
        val bus = newBus()
        val tickHandlers = mutableListOf<String>()
        val orderHandlers = mutableListOf<String>()
        val tradeHandlers = mutableListOf<String>()
        bus.subscribe<TickEvent> { tickHandlers.add("t1") }
        bus.subscribe<OrderEvent> { orderHandlers.add("o1") }
        bus.subscribe<OrderEvent> { orderHandlers.add("o2") }
        bus.subscribe<TradeEvent> { tradeHandlers.add("tr1") }

        bus.publish(OrderEvent(Order("ORD-0", "XAUUSD", Side.BUY, 1.0, OrderType.MARKET, null, 1000L)))

        assertThat(tickHandlers).isEmpty()
        assertThat(orderHandlers).containsExactly("o1", "o2")
        assertThat(tradeHandlers).isEmpty()
    }
}
