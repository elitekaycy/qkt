package com.qkt.marketdata.live.mt5

import com.qkt.marketdata.Tick
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5TickFeedSourceTest {
    @Test
    fun `polls MT5 gateway and emits ticks deduped by time_msc`() {
        val server = MockWebServer()
        val counter = AtomicInteger(0)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val n = counter.incrementAndGet()
                    val body =
                        if (n <= 2) {
                            // first two polls return the same broker time → second dedups
                            """{"bid":4700.0,"ask":4700.3,"last":4700.1,"flags":6,"time":1778662794,"time_msc":1778662794911,"volume":0,"volume_real":0}"""
                        } else {
                            // third onwards: newer broker time
                            """{"bid":4701.0,"ask":4701.3,"last":4701.1,"flags":6,"time":1778662795,"time_msc":1778662795200,"volume":0,"volume_real":0}"""
                        }
                    return MockResponse().setBody(body)
                }
            }
        server.start()
        try {
            val source =
                Mt5TickFeedSource(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    symbolMap = mapOf("XAUUSDm" to "EXNESS:XAUUSD"),
                    pollIntervalMs = 5L,
                    http = OkHttpClient(),
                )
            val captured = CopyOnWriteArrayList<Tick>()
            source.start(onTick = { captured.add(it) }, onError = { it.printStackTrace() }, onDisconnect = {})
            val deadline = System.currentTimeMillis() + 3_000L
            while (captured.size < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L)
            }
            source.stop()
            assertThat(counter.get())
                .withFailMessage("dispatcher only saw ${counter.get()} requests; raise pollIntervalMs or deadline")
                .isGreaterThanOrEqualTo(3)
            assertThat(captured).hasSize(2)
            assertThat(captured.map { it.price.toPlainString() })
                .containsExactly("4700.10000000", "4701.10000000")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `falls back to bid-ask mid when last is zero`() {
        val server = MockWebServer()
        val counter = AtomicInteger(0)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // Quote-driven instrument: bid/ask populated, last = 0 (Exness XAUUSD).
                    val ms = 1778662794911L + counter.incrementAndGet()
                    return MockResponse().setBody(
                        """{"bid":4700.0,"ask":4700.4,"last":0.0,"flags":6,"time":1778662794,"time_msc":$ms,"volume":0,"volume_real":0}""",
                    )
                }
            }
        server.start()
        try {
            val source =
                Mt5TickFeedSource(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    symbolMap = mapOf("XAUUSDm" to "EXNESS:XAUUSD"),
                    pollIntervalMs = 5L,
                    http = OkHttpClient(),
                )
            val captured = CopyOnWriteArrayList<Tick>()
            source.start(onTick = { captured.add(it) }, onError = { it.printStackTrace() }, onDisconnect = {})
            val deadline = System.currentTimeMillis() + 3_000L
            while (captured.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L)
            }
            source.stop()
            assertThat(captured).isNotEmpty
            assertThat(captured.first().price.toPlainString()).isEqualTo("4700.20000000")
        } finally {
            server.shutdown()
        }
    }
}
