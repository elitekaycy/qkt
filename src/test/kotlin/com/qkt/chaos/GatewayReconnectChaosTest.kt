package com.qkt.chaos

import com.qkt.connector.gateway.FakeGateway
import com.qkt.connector.gateway.GatewayHarness
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Chaos framing for venue recovery through a gateway: a fill made while qkt's event stream was cut is replayed when
 * the stream resyncs, and resyncing again and again never books it twice.
 */
internal class GatewayReconnectChaosTest : GatewayHarness() {
    @Test
    fun `a fill missed while disconnected is booked once, however often the stream resyncs`() {
        val a = Strategy()
        broker(session(), a, "a").submit(market("a-1", "a"))
        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() && fake.streams > 0 }
        fake.quiet = true
        fake.act { fill(wire("a-1"), "f1", "0.1", "650", FakeGateway.TIME) }
        fake.quiet = false

        fake.reset()
        await { a.of<BrokerEvent.OrderFilled>().isNotEmpty() }
        repeat(3) {
            fake.reset()
            Thread.sleep(60)
        }

        assertThat(a.of<BrokerEvent.OrderFilled>().map { it.clientOrderId }).containsExactly("a-1")
    }
}
