package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5BrokerEventsTest {
    private val clock = FixedClock(1_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val down = mutableListOf<BrokerEvent.GatewayUnreachable>()
    private val back = mutableListOf<BrokerEvent.ConnectionChanged>()
    private val events =
        MT5BrokerEvents(MT5DefaultProfiles.exness.copy(gatewayUrl = "http://127.0.0.1:1"), bus, clock).also {
            bus.subscribe<BrokerEvent.GatewayUnreachable> { e -> down.add(e) }
            bus.subscribe<BrokerEvent.ConnectionChanged> { e -> back.add(e) }
        }

    @Test
    fun `two pollers reporting the same outage raise one alert`() {
        events.publishGatewayUnreachable(3)
        events.publishGatewayUnreachable(4)

        assertThat(down.map { it.consecutiveFailures }).containsExactly(3)
    }

    @Test
    fun `recovery is announced once and only after an outage`() {
        events.publishGatewayRecovered(0)
        assertThat(back).isEmpty()

        events.publishGatewayUnreachable(3)
        events.publishGatewayRecovered(3)
        events.publishGatewayRecovered(3)

        assertThat(back.map { it.state }).containsExactly(BrokerEvent.ConnectionState.RECONNECTED)
    }

    @Test
    fun `a second outage after recovery alerts again`() {
        events.publishGatewayUnreachable(3)
        events.publishGatewayRecovered(3)
        events.publishGatewayUnreachable(5)

        assertThat(down.map { it.consecutiveFailures }).containsExactly(3, 5)
    }
}
