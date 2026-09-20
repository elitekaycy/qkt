package com.qkt.bus

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EventBusSubscriberFailureTest : EventBusFixture() {
    @Test
    fun `subscriber exception propagates out of publish`() {
        val bus = newBus()
        bus.subscribe<TickEvent> { error("boom") }

        assertThatThrownBy { bus.publish(TickEvent(tick())) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("boom")
    }

    @Test
    fun `a throwing subscriber does not skip the rest, and the failure still surfaces`() {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val ran = mutableListOf<String>()
        bus.subscribe<TickEvent> { ran.add("first") }
        bus.subscribe<TickEvent> { error("boom in the middle") }
        bus.subscribe<TickEvent> { ran.add("third") }

        org.assertj.core.api.Assertions
            .assertThatThrownBy { bus.publish(TickEvent(Tick("X", Money.of("1"), 1L))) }
            .hasMessageContaining("boom in the middle")

        // The third subscriber still ran — a venue-mutating handler upstream can no
        // longer leave the book-applier silently skipped.
        assertThat(ran).containsExactly("first", "third")
    }
}
