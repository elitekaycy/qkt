package com.qkt.marketdata.live.mt5

import com.qkt.common.SessionAnchor
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5TickFeedSourceCalendarTest {
    private object AlwaysClosed : TradingCalendar {
        override val name: String = "always-closed"

        override fun isInSession(
            symbol: String,
            t: Instant,
        ): Boolean = false

        override fun sessionRange(
            symbol: String,
            t: Instant,
        ): TimeRange = TimeRange(Instant.EPOCH, Instant.EPOCH)

        override fun anchorEpochFor(
            anchor: SessionAnchor,
            t: Instant,
        ): Long = 0L

        override fun rangeFor(
            anchor: SessionAnchor,
            anchorEpoch: Long,
        ): TimeRange = TimeRange(Instant.EPOCH, Instant.EPOCH)
    }

    @Test
    fun `out-of-session skip avoids hitting the gateway`() {
        val server = MockWebServer()
        val requestCount = AtomicInteger(0)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestCount.incrementAndGet()
                    return MockResponse().setBody(
                        """{"bid":1.0,"ask":1.0,"last":1.0,"flags":6,"time":1,"time_msc":1000,"volume":0,"volume_real":0}""",
                    )
                }
            }
        server.start()
        try {
            val source =
                Mt5TickFeedSource(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    symbols = listOf("XAUUSDm"),
                    pollIntervalMs = 5L,
                    http = OkHttpClient(),
                    calendar = AlwaysClosed,
                    outOfSessionSleepMs = 60_000L,
                )
            val captured = CopyOnWriteArrayList<Tick>()
            source.start(onTick = { captured.add(it) }, onError = {}, onDisconnect = {})
            Thread.sleep(300L)
            source.stop()
            assertThat(requestCount.get()).isZero
            assertThat(captured).isEmpty()
        } finally {
            server.shutdown()
        }
    }
}
