package com.qkt.bus

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.Event
import com.qkt.events.OrderEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EventBusDispatchTest : EventBusFixture() {
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

        val event = TickEvent(tick("XAUUSD", Money.of("2400.5")))
        bus.publish(event)

        assertThat(received).hasSize(1)
        assertThat(received[0].tick).isEqualTo(event.tick)
    }

    @Test
    fun `subscribeAll sees every stamped event after exact subscribers`() {
        val bus = newBus()
        val order = mutableListOf<String>()
        val tapped = mutableListOf<Event>()
        bus.subscribe<TickEvent> { order.add("exact:${it.sequenceId}") }
        bus.subscribeAll {
            order.add("tap:${it.sequenceId}")
            tapped.add(it)
        }

        bus.publish(TickEvent(tick()))
        bus.publish(SignalEvent(Signal.Buy("XAUUSD", Money.of("1"))))

        assertThat(order).containsExactly("exact:0", "tap:0", "tap:1")
        assertThat(tapped.map { it.sequenceId }).containsExactly(0L, 1L)
    }

    @Test
    fun `subscribeAll failure does not fail publish or skip event subscribers`() {
        val bus = newBus()
        val received = mutableListOf<TickEvent>()
        bus.subscribeAll { error("audit sink down") }
        bus.subscribe<TickEvent> { received.add(it) }

        bus.publish(TickEvent(tick()))

        assertThat(received).hasSize(1)
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
            bus.publish(SignalEvent(Signal.Buy("XAUUSD", Money.of("1"))))
            sequence.add("tick-A-end")
        }
        bus.subscribe<TickEvent> { sequence.add("tick-B") }
        bus.subscribe<SignalEvent> { sequence.add("signal-handler") }

        bus.publish(TickEvent(tick()))

        assertThat(sequence).containsExactly("tick-A-start", "signal-handler", "tick-A-end", "tick-B")
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

        bus.publish(
            OrderEvent(
                OrderRequest.Market(
                    id = "ORD-0",
                    symbol = "XAUUSD",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1000L,
                ),
            ),
        )

        assertThat(tickHandlers).isEmpty()
        assertThat(orderHandlers).containsExactly("o1", "o2")
        assertThat(tradeHandlers).isEmpty()
    }

    @Test
    fun `subscribeFirst runs ahead of earlier-registered handlers`() {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val order = mutableListOf<String>()
        bus.subscribe<TickEvent> { order.add("venue-side-effects") }
        bus.subscribeFirst<TickEvent> { order.add("book-applier") }

        bus.publish(TickEvent(Tick("X", Money.of("1"), 1L)))

        assertThat(order).containsExactly("book-applier", "venue-side-effects")
    }
}
