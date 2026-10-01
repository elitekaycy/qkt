package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.SignalEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.InsightsSink
import com.qkt.strategy.Signal
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * `signal.structure` ships only with the opt-in `structure` family: a collector that does not know the
 * type rejects the whole batch carrying it, which would drop the fills and closes batched beside it.
 */
class InsightsStructureFamilyTest {
    private val server = MockWebServer().apply { start() }

    @AfterEach
    fun stop() = server.shutdown()

    private fun bodies(families: Set<InsightsEventFamily>): String {
        repeat(5) { server.enqueue(MockResponse().setBody("""{"accepted":1}""")) }
        val sink =
            InsightsSink(
                server.url("/ingest").toString(),
                "t",
                "qkt-test",
                batchSize = 100,
                flushIntervalMs = 20L,
                queueCapacity = 100,
            )
        val bus = EventBus(FixedClock(1L), MonotonicSequenceGenerator())
        InsightsBusWiring(families).wire(bus, sink, MarketPriceTracker())
        val leg =
            OrderRequest.Market(
                "o-1",
                "DERIBIT:BTC_USDC_9OCT26_82000_P",
                Side.SELL,
                BigDecimal("0.1"),
                TimeInForce.GTC,
                1L,
                "s",
            )
        try {
            bus.publish(SignalEvent(Signal.SubmitGroup("ps-1", "ps", listOf(leg)), strategyId = "s"))
            bus.publish(SignalEvent(Signal.Buy("EXNESS:XAUUSD", BigDecimal.ONE), strategyId = "s"))
            val text = StringBuilder()
            val deadline = System.currentTimeMillis() + 1_000L
            while (!text.contains("XAUUSD") && System.currentTimeMillis() < deadline) {
                server.takeRequest(50, TimeUnit.MILLISECONDS)?.let { text.append(it.body.readUtf8()) }
            }
            return text.toString()
        } finally {
            sink.close()
        }
    }

    @Test
    fun `by default a structure signal is not sent, while an ordinary signal still is`() {
        val sent = bodies(setOf(InsightsEventFamily.SIGNAL))

        assertThat(sent).contains("XAUUSD")
        assertThat(sent).doesNotContain("signal.structure")
    }

    @Test
    fun `the structure family sends structure signals`() {
        assertThat(
            bodies(setOf(InsightsEventFamily.SIGNAL, InsightsEventFamily.STRUCTURE)),
        ).contains("\"type\":\"signal.structure\"")
    }
}
