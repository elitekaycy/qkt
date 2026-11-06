package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.InsightsSink
import java.math.BigDecimal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class InsightsBusWiringBrokerTest {
    private val server = MockWebServer().apply { start() }

    @AfterEach
    fun stop() = server.shutdown()

    private fun posted(
        families: Set<InsightsEventFamily>,
        publish: (EventBus) -> Unit,
    ): String {
        repeat(10) { server.enqueue(MockResponse().setBody("""{"accepted":1}""")) }
        val sink = InsightsSink(server.url("/ingest").toString(), "t", "qkt-test", flushIntervalMs = 20L)
        val bus = EventBus(FixedClock(3L), MonotonicSequenceGenerator())
        InsightsBusWiring(families).wire(bus, sink, MarketPriceTracker())
        try {
            publish(bus)
        } finally {
            // close() drains whatever is still queued before it returns.
            sink.close()
        }
        return (0 until server.requestCount).joinToString("\n") { server.takeRequest().body.readUtf8() }
    }

    @Test
    fun `a partial fill is sent with its cumulative quantity and exit reason`() {
        val bodies =
            posted(setOf(InsightsEventFamily.ORDER)) { bus ->
                bus.publish(
                    BrokerEvent.OrderPartiallyFilled(
                        clientOrderId = "o-1",
                        brokerOrderId = "b-1",
                        symbol = "XAUUSD",
                        side = Side.SELL,
                        price = BigDecimal("2001.5"),
                        quantity = BigDecimal("0.2"),
                        cumulativeFilled = BigDecimal("0.2"),
                        strategyId = "alpha",
                        exitReason = ExitReason.STOP,
                    ),
                )
            }

        assertThat(bodies)
            .contains("\"type\":\"order.partially_filled\"", "\"orderId\":\"o-1\"", "\"exitReason\":\"STOP\"")
    }

    @Test
    fun `gateway outages and connection changes are sent as broker lifecycle events`() {
        val bodies =
            posted(setOf(InsightsEventFamily.LIFECYCLE)) { bus ->
                bus.publish(BrokerEvent.GatewayUnreachable("mt5", 4))
                bus.publish(BrokerEvent.ConnectionChanged("mt5", BrokerEvent.ConnectionState.RECONNECTED, "back"))
            }

        assertThat(bodies).contains("\"type\":\"broker.disconnected\"", "gateway-unreachable")
        assertThat(bodies).contains("\"type\":\"broker.reconnected\"", "\"state\":\"reconnected\"")
    }

    @Test
    fun `broker lifecycle stays home without the lifecycle family`() {
        val bodies =
            posted(setOf(InsightsEventFamily.ORDER)) { bus ->
                bus.publish(BrokerEvent.ConnectionChanged("mt5", BrokerEvent.ConnectionState.CONNECTED))
            }

        assertThat(bodies).doesNotContain("broker.connected")
    }
}
