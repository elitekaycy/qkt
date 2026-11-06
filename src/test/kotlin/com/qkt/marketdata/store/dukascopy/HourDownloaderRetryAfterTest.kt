package com.qkt.marketdata.store.dukascopy

import com.qkt.common.FixedClock
import java.time.Instant
import java.time.LocalDate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class HourDownloaderRetryAfterTest {
    private val server = MockWebServer().also { it.start() }
    private val sleeps = mutableListOf<Long>()
    private val notices = mutableListOf<String>()

    @AfterEach
    fun teardown() = server.shutdown()

    private fun downloader() =
        OkHttpHourDownloader(
            baseUrl = server.url("/").toString().removeSuffix("/"),
            fallbackBaseUrls = emptyList(),
            sleeper = { sleeps += it },
            clock = FixedClock(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()),
            notice = { notices += it },
        )

    private fun limited(retryAfter: String) = MockResponse().setResponseCode(429).setHeader("Retry-After", retryAfter)

    @Test
    fun `a rate limit with an HTTP date waits until that date`() {
        server.enqueue(limited("Thu, 01 Jan 2026 00:00:30 GMT"))
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1))))

        val bytes = downloader().download("XAUUSD", LocalDate.of(2024, 3, 5), hour = 9)

        assertThat(bytes).containsExactly(1)
        assertThat(sleeps).containsExactly(30_000L)
        assertThat(notices.single()).contains("HTTP 429").contains("retrying in 30s")
    }

    @Test
    fun `an HTTP date already past and an unreadable value fall back to the backoff`() {
        server.enqueue(limited("Wed, 31 Dec 2025 23:00:00 GMT"))
        server.enqueue(limited("soon"))
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(2))))

        val bytes = downloader().download("XAUUSD", LocalDate.of(2024, 3, 5), hour = 9)

        assertThat(bytes).containsExactly(2)
        assertThat(sleeps).containsExactly(1_000L, 2_000L)
    }
}
