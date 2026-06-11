package com.qkt.observe.insights

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InsightsSinkTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun sink(
        batchSize: Int = 10,
        flushIntervalMs: Long = 50L,
        queueCapacity: Int = 100,
        failureBackoffMs: Long = 10L,
    ) = InsightsSink(
        url = server.url("/ingest").toString(),
        token = "secret",
        instanceId = "qkt-test",
        batchSize = batchSize,
        flushIntervalMs = flushIntervalMs,
        queueCapacity = queueCapacity,
        failureBackoffMs = failureBackoffMs,
    )

    private fun envelope(n: Long): InsightsEnvelope =
        InsightsEnvelope(
            id = "e$n",
            seq = n,
            ts = 1718000000000L + n,
            strategyId = "latch",
            type = "trade",
            payload =
                mapOf(
                    "orderId" to "o$n",
                    "symbol" to "XAUUSD",
                    "side" to "BUY",
                    "price" to java.math.BigDecimal("2350.5"),
                    "qty" to java.math.BigDecimal("0.1"),
                    "ts" to 1718000000000L + n,
                ),
        )

    @Test
    fun `posts a batch with bearer token, instanceId and contract-shaped envelopes`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}"""))
        val s = sink()
        s.offer(envelope(1))
        val req = server.takeRequest(2, TimeUnit.SECONDS)
        assertThat(req).isNotNull
        assertThat(req!!.getHeader("Authorization")).isEqualTo("Bearer secret")
        val body = req.body.readUtf8()
        assertThat(body).startsWith("""{"instanceId":"qkt-test","events":[""")
        assertThat(body).contains(""""v":1""")
        assertThat(body).contains(""""type":"trade"""")
        assertThat(body).contains(""""price":2350.5""")
        assertThat(body).contains(""""strategyId":"latch"""")
        s.close()
        assertThat(s.sent.get()).isEqualTo(1L)
    }

    @Test
    fun `batches up to batchSize in a single POST`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":5}"""))
        val s = sink(batchSize = 5, flushIntervalMs = 300L)
        repeat(5) { s.offer(envelope(it.toLong())) }
        val req = server.takeRequest(2, TimeUnit.SECONDS)
        assertThat(req).isNotNull
        val body = req!!.body.readUtf8()
        repeat(5) { assertThat(body).contains("\"id\":\"e$it\"") }
        s.close()
    }

    @Test
    fun `offer never blocks - full queue drops oldest and counts it`() {
        // No enqueued response: the drain thread stalls on the first POST while we flood.
        server.enqueue(MockResponse().setResponseCode(500).setHeadersDelay(2, TimeUnit.SECONDS))
        val s = sink(queueCapacity = 10, flushIntervalMs = 1_000L)
        val start = System.nanoTime()
        repeat(1_000) { s.offer(envelope(it.toLong())) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(elapsedMs).isLessThan(500)
        assertThat(s.dropped.get()).isGreaterThan(0)
        s.close()
    }

    @Test
    fun `retries a failed batch and delivers it without loss`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}"""))
        val s = sink(flushIntervalMs = 20L, failureBackoffMs = 10L)
        s.offer(envelope(1))
        val first = server.takeRequest(2, TimeUnit.SECONDS)
        val second = server.takeRequest(2, TimeUnit.SECONDS)
        assertThat(first).isNotNull
        assertThat(second).isNotNull
        // Same batch re-posted, not discarded.
        assertThat(second!!.body.readUtf8()).contains("\"id\":\"e1\"")
        s.close()
        assertThat(s.sent.get()).isEqualTo(1L)
        assertThat(s.failed.get()).isEqualTo(1L)
        assertThat(s.dropped.get()).isEqualTo(0L)
    }

    @Test
    fun `drops a batch after exhausting retries and keeps draining`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}"""))
        val s = sink(flushIntervalMs = 20L, failureBackoffMs = 10L)
        s.offer(envelope(1))
        repeat(3) { assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull }
        s.offer(envelope(2))
        val recovered = server.takeRequest(2, TimeUnit.SECONDS)
        assertThat(recovered).isNotNull
        val body = recovered!!.body.readUtf8()
        assertThat(body).contains("\"id\":\"e2\"")
        assertThat(body).doesNotContain("\"id\":\"e1\"")
        s.close()
        assertThat(s.failed.get()).isEqualTo(3L)
        assertThat(s.dropped.get()).isEqualTo(1L)
        assertThat(s.sent.get()).isEqualTo(1L)
    }
}
