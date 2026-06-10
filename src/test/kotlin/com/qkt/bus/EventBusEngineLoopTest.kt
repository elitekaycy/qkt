package com.qkt.bus

import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EventBusEngineLoopTest {
    private fun newBus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun fill(id: String) =
        BrokerEvent.OrderFilled(
            clientOrderId = id,
            brokerOrderId = id,
            symbol = "X",
            side = Side.BUY,
            price = BigDecimal.ONE,
            quantity = BigDecimal.ONE,
            timestamp = 0L,
        )

    @Test
    fun `with no engine loop bound, publish dispatches inline on the caller thread`() {
        val bus = newBus()
        val seen = mutableListOf<String>()
        bus.subscribe<BrokerEvent.OrderFilled> { seen.add(it.clientOrderId) }

        bus.publish(fill("a"))

        assertThat(seen).containsExactly("a")
    }

    @Test
    fun `a publish FROM the engine thread dispatches inline, not through the sink`() {
        val bus = newBus()
        val sink = LinkedBlockingQueue<com.qkt.events.Event>()
        val seen = mutableListOf<String>()
        bus.subscribe<BrokerEvent.OrderFilled> { seen.add((it as BrokerEvent.OrderFilled).clientOrderId) }

        val started = CountDownLatch(1)
        val engine =
            Thread {
                started.countDown()
                bus.publish(fill("engine"))
            }
        bus.bindEngineLoop(engine) { ev -> sink.put(ev) }
        engine.start()
        engine.join(2000)

        // Ran inline on the engine thread; nothing was enqueued.
        assertThat(seen).containsExactly("engine")
        assertThat(sink).isEmpty()
    }

    @Test
    fun `a publish from a NON-engine thread is rerouted to the sink, not dispatched directly`() {
        val bus = newBus()
        val sink = LinkedBlockingQueue<com.qkt.events.Event>()
        val seen = mutableListOf<String>()
        bus.subscribe<BrokerEvent.OrderFilled> { seen.add((it as BrokerEvent.OrderFilled).clientOrderId) }

        // The "engine thread" is this thread; the publish happens on ANOTHER thread.
        bus.bindEngineLoop(Thread.currentThread()) { ev -> sink.put(ev) }
        val poller = Thread { bus.publish(fill("poller")) }
        poller.start()
        poller.join(2000)

        // Not dispatched to subscribers directly; handed to the engine-loop sink instead.
        assertThat(seen).isEmpty()
        val routed = sink.poll(2, TimeUnit.SECONDS) as BrokerEvent.OrderFilled
        assertThat(routed.clientOrderId).isEqualTo("poller")
        // And when the engine thread drains it, publishing inline reaches subscribers.
        bus.publish(routed)
        assertThat(seen).containsExactly("poller")
    }

    @Test
    fun `rerouted ticks are also handed to the sink off-thread`() {
        val bus = newBus()
        val sink = LinkedBlockingQueue<com.qkt.events.Event>()
        bus.bindEngineLoop(Thread.currentThread()) { ev -> sink.put(ev) }

        val t = Thread { bus.publish(TickEvent(Tick("X", BigDecimal.ONE, 1L))) }
        t.start()
        t.join(2000)

        assertThat(sink.poll(2, TimeUnit.SECONDS)).isInstanceOf(TickEvent::class.java)
    }

    @Test
    fun `events published before the engine thread exists queue through the sink`() {
        // Brokers (and their pollers) construct before the engine loop starts. With
        // only the sink bound, an early publish must queue — dispatching inline would
        // run subscribers against a half-built pipeline on the publisher's thread.
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val queued = mutableListOf<com.qkt.events.Event>()
        bus.bindSink { queued.add(it) }
        var dispatchedInline = false
        bus.subscribe<TickEvent> { dispatchedInline = true }

        bus.publish(TickEvent(Tick("X", BigDecimal.ONE, 1L)))

        assertThat(dispatchedInline).isFalse()
        assertThat(queued).hasSize(1)
    }
}
