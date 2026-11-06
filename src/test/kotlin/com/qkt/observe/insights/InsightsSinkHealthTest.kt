package com.qkt.observe.insights

import java.net.InetAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InsightsSinkHealthTest {
    private val server = MockWebServer()
    private val bodies = CopyOnWriteArrayList<String>()
    private val calls = AtomicInteger(0)

    /** Answers with [status] for each request body; records every body. */
    private fun answer(status: (call: Int, body: String) -> Int) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readUtf8()
                    bodies += body
                    return MockResponse().setResponseCode(status(calls.incrementAndGet(), body))
                }
            }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @AfterEach
    fun teardown() = server.shutdown()

    private fun sink(
        healthIntervalMs: Long,
        maxPostAttempts: Int = 3,
        journalDir: Path? = null,
    ) = InsightsSink(
        url = "http://127.0.0.1:${server.port}/ingest",
        token = "secret",
        instanceId = "qkt-test",
        flushIntervalMs = 20L,
        failureBackoffMs = 10L,
        maxPostAttempts = maxPostAttempts,
        healthIntervalMs = healthIntervalMs,
        journalDir = journalDir,
    )

    private fun envelope() = InsightsEnvelope("e1", 1L, 1L, "s", "signal", mapOf("symbol" to "XAUUSD"))

    private fun awaitUntil(predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    private fun healthBodies() = bodies.filter { it.contains("\"type\":\"insights.health\"") }

    @Test
    fun `a drained batch is followed by a health snapshot counting it as sent`() {
        answer { _, _ -> 200 }
        val s = sink(healthIntervalMs = 1L)
        s.offer(envelope())

        assertThat(awaitUntil { healthBodies().any { it.contains("\"sent\":1") } }).isTrue
        s.close()
        assertThat(bodies).anyMatch { it.contains("\"id\":\"e1\"") }
    }

    @Test
    fun `a rejected health snapshot counts as failed and dropped`() {
        answer { _, body -> if (body.contains("insights.health")) 503 else 200 }
        val s = sink(healthIntervalMs = 1L)

        assertThat(awaitUntil { s.failed.get() >= 1L && s.dropped.get() >= 1L }).isTrue
        s.close()
        assertThat(s.sent.get()).isZero
        assertThat(healthBodies()).isNotEmpty
    }

    @Test
    fun `a journaled sink spools health snapshots through the journal`(
        @TempDir dir: Path,
    ) {
        answer { _, _ -> 200 }
        val s = sink(healthIntervalMs = 1L, journalDir = dir)

        assertThat(awaitUntil { healthBodies().any { it.contains("\"journalEnabled\":true") } }).isTrue
        s.close()
    }

    @Test
    fun `a journaled sink retries a failed post after backing off`(
        @TempDir dir: Path,
    ) {
        answer { call, _ -> if (call == 1) 500 else 200 }
        val s = sink(healthIntervalMs = 0L, maxPostAttempts = 2, journalDir = dir)
        s.offer(envelope())

        assertThat(awaitUntil { s.sent.get() == 1L }).isTrue
        s.close()
        assertThat(s.failed.get()).isEqualTo(1L)
        assertThat(s.dropped.get()).isZero
        assertThat(bodies).hasSize(2).allMatch { it.contains("\"id\":\"e1\"") }
    }
}
