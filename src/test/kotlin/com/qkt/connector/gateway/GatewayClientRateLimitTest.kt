package com.qkt.connector.gateway

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A gateway over its venue's rate limit is given time to recover, not asked again at once (#1663). */
class GatewayClientRateLimitTest {
    private val answers = LinkedBlockingQueue<MockResponse>()
    private val sentAtMs = CopyOnWriteArrayList<Long>()
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        sentAtMs += System.nanoTime() / NANOS_PER_MS
                        return answers.poll() ?: MockResponse().setResponseCode(500)
                    }
                }
            start()
        }
    private val url = server.url("/").toString().trimEnd('/')

    @AfterEach
    fun stop() = server.shutdown()

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val limited = """{"error":{"code":"venue_unavailable","message":"rate limit exceeded"}}"""
    private val account =
        """{"currency":"USD","balance":"1","equity":"1","margin_used":"0","margin_available":"1"}"""

    @Test
    fun `a rate-limited 503 is sent again only after a pause`() {
        answers += json(503, limited)
        answers += json(200, account)
        val client = GatewayClient(url, "k", httpTimeoutMs = 2_000, retryAttempts = 3)

        assertThat(client.account().equity).isEqualTo("1")

        assertThat(sentAtMs).hasSize(2)
        assertThat(sentAtMs[1] - sentAtMs[0]).isGreaterThanOrEqualTo(100L)
    }

    @Test
    fun `a 429 waits out the Retry-After the gateway sends, then succeeds`() {
        answers += json(429, limited).setHeader("Retry-After", "1")
        answers += json(200, account)
        val client = GatewayClient(url, "k", httpTimeoutMs = 2_000, retryAttempts = 3)

        assertThat(client.account().equity).isEqualTo("1")

        assertThat(sentAtMs).hasSize(2)
        assertThat(sentAtMs[1] - sentAtMs[0]).isGreaterThanOrEqualTo(950L)
    }

    @Test
    fun `after a rate limit the next request to the gateway waits too, whatever it asks`() {
        answers += json(429, limited).setHeader("Retry-After", "1")
        answers += json(200, account)
        val client = GatewayClient(url, "k", httpTimeoutMs = 2_000, retryAttempts = 1)
        assertThatThrownBy { client.orders() }

        assertThat(client.account().equity).isEqualTo("1")

        assertThat(sentAtMs).hasSize(2)
        assertThat(sentAtMs[1] - sentAtMs[0]).isGreaterThanOrEqualTo(950L)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
