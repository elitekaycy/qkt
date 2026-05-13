package com.qkt.marketdata.live.mt5

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import java.time.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5BarFetcherTest {
    @Test
    fun `fetchRange hits fetch_data_range and parses bars`() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """[{"close":4700.5,"high":4701,"low":4699,"open":4700,"tick_volume":100,"time":"2026-05-13T08:00:00Z"}]""",
                ),
            )
            val fetcher = Mt5BarFetcher(server.url("/").toString().trimEnd('/'))
            val candles =
                fetcher
                    .fetchRange(
                        symbol = "XAUUSDm",
                        window = TimeWindow.parse("5m"),
                        range =
                            TimeRange(
                                from = Instant.parse("2026-05-13T08:00:00Z"),
                                to = Instant.parse("2026-05-13T08:05:00Z"),
                            ),
                    ).toList()
            assertThat(candles).hasSize(1)
            assertThat(candles.first().close.toPlainString()).isEqualTo("4700.5")
            assertThat(candles.first().startTime).isEqualTo(Instant.parse("2026-05-13T08:00:00Z").toEpochMilli())
            val request = server.takeRequest()
            assertThat(request.path).contains("/fetch_data_range")
            assertThat(request.path).contains("symbol=XAUUSDm")
            assertThat(request.path).contains("timeframe=M5")
        } finally {
            server.shutdown()
        }
    }
}
