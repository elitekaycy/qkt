package com.qkt.bus

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.qkt.events.Event
import com.qkt.events.TickEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class EventBusPreDispatchTest : EventBusFixture() {
    @Test
    fun `a failing pre-dispatch tap is isolated from later taps and subscribers`() {
        val bus = newBus()
        val seen = mutableListOf<String>()
        bus.subscribeAllFirst { error("audit down") }
        bus.subscribeAllFirst { seen += "second tap" }
        bus.subscribe<TickEvent> { seen += "subscriber" }

        bus.publish(TickEvent(tick()))

        assertThat(seen).containsExactly("second tap", "subscriber")
    }

    @Test
    fun `publishing with trace logging on still stamps and dispatches the event`() {
        val logger = LoggerFactory.getLogger(EventBus::class.java) as Logger
        val previous = logger.level
        logger.level = Level.TRACE
        try {
            val bus = newBus()
            val seen = mutableListOf<Event>()
            bus.subscribeAll { seen += it }

            bus.publish(TickEvent(tick()))

            assertThat(seen.single().timestamp).isEqualTo(1000L)
        } finally {
            logger.level = previous
        }
    }
}
