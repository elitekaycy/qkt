package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.SetMembershipChanged
import com.qkt.events.SignalEvent
import com.qkt.instrument.universe.MembershipChange
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
 * `set.membership` ships only with the opt-in `sets` family: a collector before qkt-insights contract 1.7.0
 * quarantines the type, so a session sends it only once its collector says it knows it.
 */
class InsightsSetsFamilyTest {
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
        val bus = EventBus(FixedClock(5_000L), MonotonicSequenceGenerator())
        InsightsBusWiring(families).wire(bus, sink, MarketPriceTracker())
        try {
            bus.publish(SetMembershipChanged("rotator", "movers", "BINANCE:ETHUSDT", MembershipChange.JOIN, 1, 4_000L))
            bus.publish(SignalEvent(Signal.Buy("EXNESS:XAUUSD", BigDecimal.ONE), strategyId = "rotator"))
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
    fun `by default a membership change is not sent, while a signal still is`() {
        val sent = bodies(setOf(InsightsEventFamily.SIGNAL))

        assertThat(sent).contains("XAUUSD")
        assertThat(sent).doesNotContain("set.membership")
    }

    @Test
    fun `the sets family sends each membership change as a set membership envelope`() {
        val sent = bodies(setOf(InsightsEventFamily.SIGNAL, InsightsEventFamily.SETS))

        assertThat(sent)
            .contains("\"type\":\"set.membership\"")
            .contains("\"member\":\"BINANCE:ETHUSDT\"")
            .contains("\"change\":\"join\"")
            .contains("\"rank\":1")
            .contains("\"closeTs\":4000")
    }
}
