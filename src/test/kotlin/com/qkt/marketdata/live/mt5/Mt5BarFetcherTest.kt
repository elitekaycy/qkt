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

    @Test
    fun `fetchRange drops bars whose startTime is at or beyond range to (#181)`() {
        // The gateway returns three 1h bars when asked for [16:00, 18:00) — the
        // 16:00 and 17:00 bars (closed) plus the 18:00 bar (currently open).
        // Only the two closed ones should reach the caller; the open bar would
        // trip the IndicatorWarmer look-ahead check.
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """[
                      {"close":1,"high":1,"low":1,"open":1,"tick_volume":1,"time":"2026-05-29T16:00:00Z"},
                      {"close":2,"high":2,"low":2,"open":2,"tick_volume":1,"time":"2026-05-29T17:00:00Z"},
                      {"close":3,"high":3,"low":3,"open":3,"tick_volume":1,"time":"2026-05-29T18:00:00Z"}
                    ]""",
                ),
            )
            val fetcher = Mt5BarFetcher(server.url("/").toString().trimEnd('/'))
            val candles =
                fetcher
                    .fetchRange(
                        symbol = "XAUUSDm",
                        window = TimeWindow.parse("1h"),
                        range =
                            TimeRange(
                                from = Instant.parse("2026-05-29T16:00:00Z"),
                                to = Instant.parse("2026-05-29T18:00:00Z"),
                            ),
                    ).toList()
            assertThat(candles).hasSize(2)
            assertThat(candles.map { it.startTime })
                .containsExactly(
                    Instant.parse("2026-05-29T16:00:00Z").toEpochMilli(),
                    Instant.parse("2026-05-29T17:00:00Z").toEpochMilli(),
                )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fetchRange drops bars whose startTime is before range from (#181)`() {
        // Defensive symmetry: a gateway that over-fetches on the lower bound
        // shouldn't leak pre-range bars into warmup either.
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """[
                      {"close":1,"high":1,"low":1,"open":1,"tick_volume":1,"time":"2026-05-29T15:00:00Z"},
                      {"close":2,"high":2,"low":2,"open":2,"tick_volume":1,"time":"2026-05-29T16:00:00Z"}
                    ]""",
                ),
            )
            val fetcher = Mt5BarFetcher(server.url("/").toString().trimEnd('/'))
            val candles =
                fetcher
                    .fetchRange(
                        symbol = "XAUUSDm",
                        window = TimeWindow.parse("1h"),
                        range =
                            TimeRange(
                                from = Instant.parse("2026-05-29T16:00:00Z"),
                                to = Instant.parse("2026-05-29T18:00:00Z"),
                            ),
                    ).toList()
            assertThat(candles).hasSize(1)
            assertThat(candles.first().startTime)
                .isEqualTo(Instant.parse("2026-05-29T16:00:00Z").toEpochMilli())
        } finally {
            server.shutdown()
        }
    }
}
