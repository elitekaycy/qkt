package com.qkt.app

import com.qkt.common.FixedClock
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.InsightsSink
import com.qkt.persistence.PersistenceHealth
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class InsightsLifecycleFeedEventsTest {
    private val server = MockWebServer().apply { start() }

    @AfterEach
    fun stop() = server.shutdown()

    private val source =
        object : MarketSource {
            override val name = "mt5"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true
        }

    private class HookedFeed :
        TickFeed,
        MarketDataLifecycleFeed {
        var disconnect: ((MarketDataFeedScope) -> Unit)? = null
        var reconnect: ((MarketDataFeedScope) -> Unit)? = null

        override fun next(): Tick? = null

        override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
            disconnect = handler
        }

        override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
            reconnect = handler
        }
    }

    /** Runs [emit] against a lifecycle over a real sink, then returns every body the sink posted. */
    private fun posted(
        families: Set<InsightsEventFamily>,
        emit: (InsightsLifecycle) -> Unit,
    ): String {
        repeat(10) { server.enqueue(MockResponse().setBody("""{"accepted":1}""")) }
        val sink = InsightsSink(server.url("/ingest").toString(), "t", "qkt-test", flushIntervalMs = 20L)
        val lifecycle = InsightsLifecycle(sink, families, emptyMap(), emptyList(), source, listOf("X"), FixedClock(9L))
        try {
            emit(lifecycle)
        } finally {
            // close() drains whatever is still queued before it returns.
            sink.close()
        }
        return (0 until server.requestCount).joinToString("\n") { server.takeRequest().body.readUtf8() }
    }

    @Test
    fun `feed disconnects and reconnects are announced with the scope or the session defaults`() {
        val feed = HookedFeed()

        val bodies =
            posted(setOf(InsightsEventFamily.LIFECYCLE)) { lifecycle ->
                lifecycle.connected({ emptyList() }, feed)
                feed.disconnect!!(MarketDataFeedScope())
                feed.disconnect!!(MarketDataFeedScope(source = "bybit", symbols = listOf("BTCUSDT")))
                feed.reconnect!!(MarketDataFeedScope())
                feed.reconnect!!(MarketDataFeedScope(source = "bybit", symbols = listOf("ETHUSDT")))
            }

        assertThat(bodies).contains("\"type\":\"marketdata.connected\"")
        assertThat(Regex("\"type\":\"marketdata.disconnected\"").findAll(bodies).count()).isEqualTo(2)
        assertThat(Regex("\"type\":\"marketdata.reconnected\"").findAll(bodies).count()).isEqualTo(2)
        assertThat(bodies).contains("source-disconnected", "source-reconnected", "BTCUSDT", "ETHUSDT", "bybit")
    }

    @Test
    fun `a session without the lifecycle family never hooks the feed`() {
        val feed = HookedFeed()

        val bodies = posted(setOf(InsightsEventFamily.TRADE)) { it.connected({ emptyList() }, feed) }

        assertThat(feed.disconnect).isNull()
        assertThat(feed.reconnect).isNull()
        assertThat(bodies).doesNotContain("marketdata.connected")
    }

    @Test
    fun `failing persistence ships a state snapshot owned by the strategy or the session`() {
        val health = PersistenceHealth(enabled = true, totalWrites = 7, failedWrites = 2)

        val bodies =
            posted(setOf(InsightsEventFamily.STATE)) { lifecycle ->
                lifecycle.persistenceFailing("alpha", health)
                lifecycle.persistenceFailing("", health)
            }

        assertThat(bodies).contains("\"type\":\"state.persistence\"", "persistence-alpha-9", "persistence-session-9")
        assertThat(bodies).contains("\"failedWrites\":2")
    }

    @Test
    fun `persistence snapshots stay home without the state family`() {
        val bodies =
            posted(setOf(InsightsEventFamily.LIFECYCLE)) { it.persistenceFailing("alpha", PersistenceHealth(true)) }

        assertThat(bodies).doesNotContain("state.persistence")
    }
}
