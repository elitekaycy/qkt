package com.qkt.app

import com.qkt.common.FixedClock
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.InsightsSink
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `marketdata.recovered` ships only with the opt-in `marketdata` family: a collector older than
 * qkt-insights #106 rejects the whole batch that carries it (seen in the v0.54.0 attestation),
 * which would drop the trades and fills batched beside it.
 */
class InsightsLifecycleMarketDataTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        repeat(10) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}""")) }
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private val source =
        object : MarketSource {
            override val name = "mt5"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true
        }

    private fun bodiesAfterStaleAndRecovery(families: Set<InsightsEventFamily>): String {
        val sink =
            InsightsSink(
                url = server.url("/ingest").toString(),
                token = "secret",
                instanceId = "qkt-test",
                batchSize = 100,
                flushIntervalMs = 20L,
                queueCapacity = 100,
            )
        val lifecycle = InsightsLifecycle(sink, families, emptyMap(), emptyList(), source, listOf("X"), FixedClock(1L))
        try {
            lifecycle.marketDataStale("X", "quote age 61000ms exceeds 60000ms threshold", "stale")
            lifecycle.marketDataRecovered("X", "fresh tick after stale", 61_000L)
            val bodies = StringBuilder()
            val deadline = System.currentTimeMillis() + 1_000L
            while (!bodies.contains("marketdata.stale") && System.currentTimeMillis() < deadline) {
                server.takeRequest(50, TimeUnit.MILLISECONDS)?.let { bodies.append(it.body.readUtf8()) }
            }
            server.takeRequest(100, TimeUnit.MILLISECONDS)?.let { bodies.append(it.body.readUtf8()) }
            return bodies.toString()
        } finally {
            sink.close()
        }
    }

    @Test
    fun `lifecycle alone sends stale but never the recovery event`() {
        val bodies = bodiesAfterStaleAndRecovery(setOf(InsightsEventFamily.LIFECYCLE))

        assertThat(bodies).contains("\"type\":\"marketdata.stale\"")
        assertThat(bodies).doesNotContain("marketdata.recovered")
    }

    @Test
    fun `the marketdata family adds the recovery event`() {
        val bodies = bodiesAfterStaleAndRecovery(setOf(InsightsEventFamily.LIFECYCLE, InsightsEventFamily.MARKETDATA))

        assertThat(bodies).contains("\"type\":\"marketdata.recovered\"")
        assertThat(bodies).contains("\"unhealthyForMs\":61000")
    }
}
