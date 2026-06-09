package com.qkt.marketdata.store.dukascopy

import java.time.LocalDate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HourDownloaderTest {
    @Test
    fun `returns bytes for a present hour and null for 404`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()

        val dl = OkHttpHourDownloader(baseUrl = server.url("/").toString().removeSuffix("/"))
        val present = dl.download("XAUUSD", LocalDate.of(2024, 3, 5), hour = 9)
        val absent = dl.download("XAUUSD", LocalDate.of(2024, 3, 5), hour = 10)

        assertThat(present).containsExactly(1, 2, 3)
        assertThat(absent).isNull()

        // Month is zero-indexed in the dukascopy path: March -> /02/.
        assertThat(server.takeRequest().path).isEqualTo("/XAUUSD/2024/02/05/09h_ticks.bi5")
        server.shutdown()
    }

    @Test
    fun `retries a transient connection failure then succeeds`() {
        // The dukascopy CDN drops connections under load; a backtest must not die on a blip.
        val server = MockWebServer()
        server.enqueue(MockResponse().apply { socketPolicy = SocketPolicy.DISCONNECT_AT_START })
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(7, 8, 9))))
        server.start()

        val dl = OkHttpHourDownloader(baseUrl = server.url("/").toString().removeSuffix("/"))
        val bytes = dl.download("XAUUSD", LocalDate.of(2024, 3, 5), hour = 9)

        assertThat(bytes).containsExactly(7, 8, 9)
        assertThat(server.requestCount).isEqualTo(2)
        server.shutdown()
    }

    @Test
    fun `gives up after max attempts on persistent failure`() {
        val server = MockWebServer()
        repeat(3) { server.enqueue(MockResponse().apply { socketPolicy = SocketPolicy.DISCONNECT_AT_START }) }
        server.start()

        val dl = OkHttpHourDownloader(baseUrl = server.url("/").toString().removeSuffix("/"))
        assertThat(
            runCatching { dl.download("XAUUSD", LocalDate.of(2024, 3, 5), hour = 9) }.exceptionOrNull(),
        ).hasMessageContaining("after 3 attempts")
        server.shutdown()
    }
}
