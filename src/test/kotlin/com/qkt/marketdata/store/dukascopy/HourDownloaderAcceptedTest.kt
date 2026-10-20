package com.qkt.marketdata.store.dukascopy

import java.time.LocalDate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * jetta.dukascopy.com answers 202 with an empty body while it is holding a request (2026-10-20, even for a symbol
 * that does not exist). That is not an hour without ticks: taking it as one stores a day with holes.
 */
class HourDownloaderAcceptedTest {
    private fun downloader(server: MockWebServer) =
        OkHttpHourDownloader(
            baseUrl = server.url("/").toString().removeSuffix("/"),
            maxAttempts = 3,
            fallbackBaseUrls = emptyList(),
            sleeper = {},
            notice = {},
        )

    @Test
    fun `a 202 is retried until the hour arrives`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(4, 5))))
        server.start()

        val bytes = downloader(server).download("XAUUSD", LocalDate.of(2026, 6, 8), hour = 14)

        assertThat(bytes).containsExactly(4, 5)
        server.shutdown()
    }

    @Test
    fun `an hour that only ever answers 202 fails instead of reading as empty`() {
        val server = MockWebServer()
        repeat(3) { server.enqueue(MockResponse().setResponseCode(202)) }
        server.start()

        val failure = runCatching { downloader(server).download("XAUUSD", LocalDate.of(2026, 6, 8), hour = 14) }

        assertThat(failure.exceptionOrNull()).hasMessageContaining("after 3 attempts")
        server.shutdown()
    }
}
