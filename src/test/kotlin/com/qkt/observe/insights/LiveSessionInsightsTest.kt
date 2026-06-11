package com.qkt.observe.insights

import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * End-to-end on the qkt side: a real [LiveSession] trades on paper and the insights
 * sink ships the resulting bus events over real HTTP to a captured collector endpoint.
 */
class LiveSessionInsightsTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        // Every batch the sink sends gets a 200 ack.
        repeat(20) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}""")) }
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private class BuyThenSell : Strategy {
        private var ticks = 0

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            ticks++
            if (ticks == 1) emit(Signal.Buy(tick.symbol, Money.of("1")))
            if (ticks == 2) emit(Signal.Sell(tick.symbol, Money.of("1")))
        }
    }

    @Test
    fun `paper session streams signal, order lifecycle and trade to the collector`() {
        val now = Instant.parse("2024-01-15T15:00:00Z")
        val src = InMemoryMarketSource()
        src.seedLive(
            "X",
            listOf(
                Tick("X", Money.of("100"), now.toEpochMilli()),
                Tick("X", Money.of("101"), now.plus(Duration.ofSeconds(1)).toEpochMilli()),
            ),
        )
        val sink =
            InsightsSink(
                url = server.url("/ingest").toString(),
                token = "secret",
                instanceId = "qkt-test",
                batchSize = 100,
                flushIntervalMs = 50L,
                queueCapacity = 1000,
            )
        val session =
            LiveSession(
                strategies = listOf("roundtrip" to BuyThenSell()),
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                insightsSink = sink,
                insightsEvents = InsightsEventFamily.entries.toSet(),
            )

        val handle = session.start()
        assertThat(handle.awaitTermination(Duration.ofSeconds(5))).isTrue()

        // Re-entrant bus dispatch means order.submit reaches the sink AFTER the nested
        // fill and trade, possibly in a later batch — collect until every marker landed.
        val markers =
            listOf(
                "\"type\":\"signal\"",
                "\"type\":\"order.submit\"",
                "\"type\":\"trade\"",
                "\"type\":\"trade.closed\"",
            )
        val bodies = StringBuilder()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val req = server.takeRequest(250, TimeUnit.MILLISECONDS) ?: continue
            assertThat(req.getHeader("Authorization")).isEqualTo("Bearer secret")
            bodies.append(req.body.readUtf8())
            if (markers.all { bodies.contains(it) }) break
        }
        sink.close()

        val all = bodies.toString()
        assertThat(all).contains("\"instanceId\":\"qkt-test\"")
        assertThat(all).contains("\"type\":\"signal\"")
        assertThat(all).contains("\"type\":\"order.submit\"")
        assertThat(all).contains("\"type\":\"trade\"")
        assertThat(all).contains("\"type\":\"trade.closed\"")
        assertThat(all).contains("\"realized\":")
        assertThat(all).contains("\"symbol\":\"X\"")
    }
}
