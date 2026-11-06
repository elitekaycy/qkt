package com.qkt.connector.gateway

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.common.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Orders left unanswered by a rate-limited gateway are asked after on one shared, paced backoff, not each every
 * second, and each is still resolved once the limit lifts (#1663: 1,425 "still unresolved" lines in 18 minutes).
 */
class GatewayPlacementRateLimitTest {
    @Volatile private var limited = true
    private val requests = AtomicInteger()
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests.incrementAndGet()
                        if (limited) {
                            return MockResponse()
                                .setResponseCode(503)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"error":{"code":"venue_unavailable","message":"rate limit exceeded"}}""")
                        }
                        return MockResponse()
                            .setResponseCode(404)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"error":{"code":"not_found","message":"no such order"}}""")
                    }
                }
            start()
        }
    private val client =
        GatewayClient(server.url("/").toString().trimEnd('/'), "k", httpTimeoutMs = 2_000, retryAttempts = 3)
    private val placement =
        GatewayPlacement(client, SystemClock(), {}, {}, submitDeadlineMs = 200, retryMs = 20)
    private val logger = LoggerFactory.getLogger(GatewayPlacement::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @AfterEach
    fun stop() {
        placement.shutdown()
        logger.detachAppender(logs)
        server.shutdown()
    }

    @Test
    fun `unanswered orders share one paced backoff and are each resolved once the limit lifts`() {
        logger.addAppender(logs)
        val rejected = CopyOnWriteArrayList<String>()
        (1..5).forEach { n ->
            val body = WireSubmit("exit-$n", "XAUUSD", "sell", "market", "1", null, null, "gtc", reduceOnly = true)
            placement.submit(body) { rejected += it }
        }

        Thread.sleep(2_000)
        val whileLimited = requests.get()
        val warnings = logs.list.count { it.level == Level.WARN }
        limited = false

        assertThat(whileLimited).isLessThanOrEqualTo(15)
        assertThat(warnings).isLessThanOrEqualTo(12)
        val deadline = System.currentTimeMillis() + 20_000
        while (rejected.size < 5 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertThat(rejected).hasSize(5).allMatch { it.contains("never received it") }
    }
}
