package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A lane bus publish from a venue's thread runs on the engine thread, before and after the loop exists. */
class LaneBusesTest {
    @Test
    fun `a lane's events published off the engine thread are handled on it, in order`() {
        val mailbox = EngineMailbox()
        val lanes = LaneBuses(mailbox)
        val bus = EventBus(FixedClock(time = 1), MonotonicSequenceGenerator())
        lanes.bind(bus)
        val handled = mutableListOf<Pair<String, String>>()
        bus.subscribe<BrokerEvent.OrderCancelled> { handled += it.clientOrderId to Thread.currentThread().name }

        Thread { bus.publish(BrokerEvent.OrderCancelled("early", null, "x", "s", 1)) }.apply { start() }.join()
        assertThat(handled).isEmpty()
        val engine =
            Thread({
                repeat(2) { (mailbox.control.take() as Inbound.Query).execute() }
            }, "engine")
        lanes.attach(engine)
        engine.start()
        Thread { bus.publish(BrokerEvent.OrderCancelled("late", null, "x", "s", 1)) }.apply { start() }.join()
        engine.join(5_000)

        assertThat(handled).containsExactly("early" to "engine", "late" to "engine")
    }
}
