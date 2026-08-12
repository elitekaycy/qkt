package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5ServerTimeZone
import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import java.time.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class Mt5BarFetcherTest {
    @Test
    fun `multi-hour warmup rejects windows that cannot align to the UTC hour grid`() {
        val fetcher = Mt5BarFetcher("http://unused")

        assertThatThrownBy {
            fetcher
                .fetchRange(
                    symbol = "XAUUSD",
                    window = TimeWindow(5_400_000L),
                    range =
                        TimeRange(
                            from = Instant.parse("2026-07-13T08:00:00Z"),
                            to = Instant.parse("2026-07-13T12:30:00Z"),
                        ),
                ).toList()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Cannot align 5400000ms bars to the UTC grid")
    }

    @Test
    fun `multi-hour warmup rejects H1 bars that are not UTC-hour aligned`() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """[{"close":1,"high":1,"low":1,"open":1,"tick_volume":1,"time":"2026-07-13T11:30:00Z"}]""",
                ),
            )
            val fetcher = Mt5BarFetcher(server.url("/").toString().trimEnd('/'))

            assertThatThrownBy {
                fetcher
                    .fetchRange(
                        symbol = "XAUUSD",
                        window = TimeWindow.parse("4h"),
                        range =
                            TimeRange(
                                from = Instant.parse("2026-07-13T08:00:00Z"),
                                to = Instant.parse("2026-07-13T12:00:00Z"),
                            ),
                    ).toList()
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("is not hour-aligned after")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `live warmup converts MT5 bid bars to mid using recorded spread`() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"point":"0.01"}"""))
            server.enqueue(
                MockResponse().setBody(
                    """[{"open":100,"high":101,"low":99,"close":100.5,"spread":20,"tick_volume":1,"time":"2026-05-13T08:00:00Z"}]""",
                ),
            )
            val fetcher =
                Mt5BarFetcher(
                    server.url("/").toString().trimEnd('/'),
                    normalizeBidBarsToMid = true,
                )

            val candle =
                fetcher
                    .fetchRange(
                        "XAUUSDm",
                        TimeWindow.parse("5m"),
                        TimeRange(
                            Instant.parse("2026-05-13T08:00:00Z"),
                            Instant.parse("2026-05-13T08:05:00Z"),
                        ),
                    ).single()

            assertThat(candle.open).isEqualByComparingTo("100.10")
            assertThat(candle.high).isEqualByComparingTo("101.10")
            assertThat(candle.low).isEqualByComparingTo("99.10")
            assertThat(candle.close).isEqualByComparingTo("100.60")
            assertThat(server.takeRequest().path).isEqualTo("/symbol_info/XAUUSDm")
            assertThat(server.takeRequest().path).contains("/fetch_data_range")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `winter broker-local bar request and response normalize to UTC`() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """[{"close":1,"high":1,"low":1,"open":1,"tick_volume":1,"time":"2026-01-15T10:00:00"}]""",
                ),
            )
            val fetcher =
                Mt5BarFetcher(
                    server.url("/").toString().trimEnd('/'),
                    serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
                )

            val candles =
                fetcher
                    .fetchRange(
                        symbol = "XAUUSDm",
                        window = TimeWindow.parse("5m"),
                        range =
                            TimeRange(
                                from = Instant.parse("2026-01-15T08:00:00Z"),
                                to = Instant.parse("2026-01-15T08:05:00Z"),
                            ),
                    ).toList()

            assertThat(candles.single().startTime)
                .isEqualTo(Instant.parse("2026-01-15T08:00:00Z").toEpochMilli())
            assertThat(server.takeRequest().path)
                .contains("start=2026-01-15T10%3A00")
                .contains("end=2026-01-15T10%3A05")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `multi-hour window fetches H1 and rebuilds bars on the UTC grid`() {
        // MT5 H4 bars are broker-day-anchored (01:00/05:00/... UTC for a summer New
        // York-close server), so the fetcher must not request H4 at all: it fetches H1
        // (hour bars stay hour-aligned in UTC) and rebuilds 4h bars on the epoch grid.
        val server = MockWebServer().apply { start() }
        try {
            val rows =
                (0 until 8).joinToString(",") { i ->
                    // Server-local 11:00..18:00 on 2026-07-13 == 08:00..15:00 UTC (UTC+3).
                    """{"open":${100 + i},"high":${200 + i},"low":${50 + i},"close":${150 + i},""" +
                        """"tick_volume":1,"time":"2026-07-13T${11 + i}:00:00"}"""
                }
            server.enqueue(MockResponse().setBody("[$rows]"))
            val fetcher =
                Mt5BarFetcher(
                    server.url("/").toString().trimEnd('/'),
                    serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
                )

            val candles =
                fetcher
                    .fetchRange(
                        symbol = "XAUUSD",
                        window = TimeWindow.parse("4h"),
                        range =
                            TimeRange(
                                from = Instant.parse("2026-07-13T08:00:00Z"),
                                to = Instant.parse("2026-07-13T16:00:00Z"),
                            ),
                    ).toList()

            assertThat(server.takeRequest().path).contains("timeframe=H1")
            assertThat(candles).hasSize(2)
            val first = candles[0]
            assertThat(first.startTime).isEqualTo(Instant.parse("2026-07-13T08:00:00Z").toEpochMilli())
            assertThat(first.endTime).isEqualTo(Instant.parse("2026-07-13T12:00:00Z").toEpochMilli())
            assertThat(first.open).isEqualByComparingTo("100")
            assertThat(first.high).isEqualByComparingTo("203")
            assertThat(first.low).isEqualByComparingTo("50")
            assertThat(first.close).isEqualByComparingTo("153")
            assertThat(first.volume).isEqualByComparingTo("4")
            val second = candles[1]
            assertThat(second.startTime).isEqualTo(Instant.parse("2026-07-13T12:00:00Z").toEpochMilli())
            assertThat(second.open).isEqualByComparingTo("104")
            assertThat(second.close).isEqualByComparingTo("157")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `partial trailing bucket is not emitted as a closed bar`() {
        val server = MockWebServer().apply { start() }
        try {
            // Only two H1 bars of the 08:00-12:00 UTC bucket exist and the range ends
            // mid-bucket — no 4h bar may close from it.
            val rows =
                (0 until 2).joinToString(",") { i ->
                    """{"open":1,"high":1,"low":1,"close":1,"tick_volume":1,"time":"2026-07-13T${11 + i}:00:00"}"""
                }
            server.enqueue(MockResponse().setBody("[$rows]"))
            val fetcher =
                Mt5BarFetcher(
                    server.url("/").toString().trimEnd('/'),
                    serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
                )

            val candles =
                fetcher
                    .fetchRange(
                        symbol = "XAUUSD",
                        window = TimeWindow.parse("4h"),
                        range =
                            TimeRange(
                                from = Instant.parse("2026-07-13T08:00:00Z"),
                                to = Instant.parse("2026-07-13T10:00:00Z"),
                            ),
                    ).toList()

            assertThat(candles).isEmpty()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `summer gateway Z label is treated as broker wall time and normalizes to UTC`() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """[{"close":1,"high":1,"low":1,"open":1,"tick_volume":1,"time":"2026-07-15T11:00:00Z"}]""",
                ),
            )
            val fetcher =
                Mt5BarFetcher(
                    server.url("/").toString().trimEnd('/'),
                    serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
                )

            val candles =
                fetcher
                    .fetchRange(
                        symbol = "XAUUSDm",
                        window = TimeWindow.parse("5m"),
                        range =
                            TimeRange(
                                from = Instant.parse("2026-07-15T08:00:00Z"),
                                to = Instant.parse("2026-07-15T08:05:00Z"),
                            ),
                    ).toList()

            assertThat(candles.single().startTime)
                .isEqualTo(Instant.parse("2026-07-15T08:00:00Z").toEpochMilli())
            assertThat(server.takeRequest().path)
                .contains("start=2026-07-15T11%3A00")
                .contains("end=2026-07-15T11%3A05")
        } finally {
            server.shutdown()
        }
    }

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
    fun `fetchRange supports 30 minute MT5 warmup bars`() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """[{"close":1.1425,"high":1.143,"low":1.141,"open":1.142,"tick_volume":100,"time":"2026-07-09T12:00:00Z"}]""",
                ),
            )
            val fetcher = Mt5BarFetcher(server.url("/").toString().trimEnd('/'))
            val candle =
                fetcher
                    .fetchRange(
                        symbol = "EURUSDm",
                        window = TimeWindow.parse("30m"),
                        range =
                            TimeRange(
                                from = Instant.parse("2026-07-09T12:00:00Z"),
                                to = Instant.parse("2026-07-09T12:30:00Z"),
                            ),
                    ).single()

            assertThat(candle.endTime).isEqualTo(Instant.parse("2026-07-09T12:30:00Z").toEpochMilli())
            val request = server.takeRequest()
            assertThat(request.path).contains("/fetch_data_range")
            assertThat(request.path).contains("symbol=EURUSDm")
            assertThat(request.path).contains("timeframe=M30")
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
    fun `fetchRange drops the forming bar when range ends inside its window`() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """[{"close":1.1,"high":1.2,"low":1.0,"open":1.05,"tick_volume":7,"time":"2026-08-09T23:30:00Z"}]""",
                ),
            )
            val fetcher = Mt5BarFetcher(server.url("/").toString().trimEnd('/'))

            val candles =
                fetcher
                    .fetchRange(
                        symbol = "EURUSDm",
                        window = TimeWindow.parse("5m"),
                        range =
                            TimeRange(
                                from = Instant.parse("2026-08-09T23:00:00Z"),
                                to = Instant.parse("2026-08-09T23:33:00Z"),
                            ),
                    ).toList()

            assertThat(candles).isEmpty()
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

    @Test
    fun `warmup wider than the gateway's 31-day cap is fetched in 30-day chunks`() {
        // 45-day 4h warmup exceeds the gateway's single-request 31-day limit, so it must
        // arrive as two fetch_data_range calls: [Jan1, Jan31) then [Jan31, Feb15).
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """[{"close":1,"high":1,"low":1,"open":1,"tick_volume":1,"time":"2026-01-05T00:00:00Z"}]""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """[{"close":2,"high":2,"low":2,"open":2,"tick_volume":1,"time":"2026-02-01T00:00:00Z"}]""",
                ),
            )
            val fetcher = Mt5BarFetcher(server.url("/").toString().trimEnd('/'))

            val candles =
                fetcher
                    .fetchRange(
                        symbol = "XAUUSD",
                        window = TimeWindow.parse("4h"),
                        range =
                            TimeRange(
                                from = Instant.parse("2026-01-01T00:00:00Z"),
                                to = Instant.parse("2026-02-15T00:00:00Z"),
                            ),
                    ).toList()

            assertThat(candles.map { it.startTime })
                .containsExactly(
                    Instant.parse("2026-01-05T00:00:00Z").toEpochMilli(),
                    Instant.parse("2026-02-01T00:00:00Z").toEpochMilli(),
                )
            val first = server.takeRequest()
            assertThat(first.path).contains("start=2026-01-01T00%3A00").contains("end=2026-01-31T00%3A00")
            val second = server.takeRequest()
            assertThat(second.path).contains("start=2026-01-31T00%3A00").contains("end=2026-02-15T00%3A00")
        } finally {
            server.shutdown()
        }
    }
}
