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

    private fun tickRow(
        ms: Long,
        bid: String,
        ask: String,
    ): String = """{"bid":$bid,"ask":$ask,"last":0.0,"flags":6,"time":${ms / 1000},"time_msc":$ms,"volume":0}"""

    @Test
    fun `sub-minute warmup is rebuilt from venue ticks with the live feed's aggregator (#1133)`() {
        val server = MockWebServer().apply { start() }
        try {
            val t0 = Instant.parse("2026-09-13T22:00:00Z").toEpochMilli()
            // Ticks over 12 s: two complete 5s bars [0,5) and [5,10), then a partial bar.
            val ticks =
                listOf(
                    Triple(t0 + 400L, "4338.10", "4338.30"),
                    Triple(t0 + 2_900L, "4338.50", "4338.70"),
                    Triple(t0 + 4_999L, "4337.90", "4338.10"),
                    Triple(t0 + 5_100L, "4338.00", "4338.20"),
                    Triple(t0 + 9_000L, "4339.00", "4339.20"),
                    Triple(t0 + 11_000L, "4340.00", "4340.20"),
                )
            val body = ticks.joinToString(",", prefix = "[", postfix = "]") { tickRow(it.first, it.second, it.third) }
            server.enqueue(MockResponse().setBody(body))
            val fetcher = Mt5BarFetcher(server.url("/").toString().trimEnd('/'))

            val candles =
                fetcher
                    .fetchRange(
                        symbol = "XAUUSDm",
                        window = TimeWindow.parse("5s"),
                        range = TimeRange(from = Instant.ofEpochMilli(t0), to = Instant.ofEpochMilli(t0 + 12_000L)),
                    ).toList()

            val request = server.takeRequest()
            assertThat(request.path).contains("/copy_ticks_range").contains("symbol=XAUUSDm")
            // The same ticks through the live aggregator must give the same bars.
            val expected = mutableListOf<com.qkt.marketdata.Candle>()
            val live =
                com.qkt.candles.CandleAggregator
                    .standalone(TimeWindow.parse("5s")) { expected.add(it) }
            ticks.forEach { (ms, bid, ask) ->
                val mid = (bid.toBigDecimal() + ask.toBigDecimal()).divide(java.math.BigDecimal(2))
                live.onTick(
                    com.qkt.marketdata.Tick(
                        symbol = "XAUUSDm",
                        price = mid.setScale(com.qkt.common.Money.SCALE, com.qkt.common.Money.ROUNDING),
                        timestamp = ms,
                        bid = bid.toBigDecimal(),
                        ask = ask.toBigDecimal(),
                    ),
                )
            }
            live.flushClosed(t0 + 12_000L)
            assertThat(candles).hasSize(2)
            assertThat(candles).isEqualTo(expected)
            assertThat(candles[0].startTime).isEqualTo(t0)
            assertThat(candles[0].endTime).isEqualTo(t0 + 5_000L)
            assertThat(candles[0].open).isEqualByComparingTo("4338.20")
            assertThat(candles[0].high).isEqualByComparingTo("4338.60")
            assertThat(candles[0].close).isEqualByComparingTo("4338.00")
            assertThat(candles[0].volume).isEqualByComparingTo("3")
            assertThat(candles[1].startTime).isEqualTo(t0 + 5_000L)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `sub-minute warmup pages the tick range and drops ticks outside the window (#1133)`() {
        val server = MockWebServer().apply { start() }
        try {
            val t0 = Instant.parse("2026-09-13T22:00:00Z").toEpochMilli()
            // 20 minutes of range → two /copy_ticks_range pages (15 min each).
            server.enqueue(
                MockResponse().setBody(
                    "[" + tickRow(t0 - 1L, "1", "1") + "," + tickRow(t0 + 1L, "2", "2") + "]",
                ),
            )
            server.enqueue(MockResponse().setBody("[" + tickRow(t0 + 16 * 60_000L, "3", "3") + "]"))
            val fetcher = Mt5BarFetcher(server.url("/").toString().trimEnd('/'))

            val candles =
                fetcher
                    .fetchRange(
                        symbol = "EURUSDm",
                        window = TimeWindow.parse("30s"),
                        range =
                            TimeRange(
                                from = Instant.ofEpochMilli(t0),
                                to = Instant.ofEpochMilli(t0 + 20 * 60_000L),
                            ),
                    ).toList()

            assertThat(server.requestCount).isEqualTo(2)
            // The tick at t0-1 precedes the window; the bar at t0 opens at 2 and the one at 16:00 at 3.
            assertThat(candles.map { it.open.toPlainString() }).containsExactly("2.00000000", "3.00000000")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `sub-minute warmup refuses a tick span longer than the cap (#1133)`() {
        val fetcher = Mt5BarFetcher("http://unused")
        assertThatThrownBy {
            fetcher
                .fetchRange(
                    symbol = "XAUUSDm",
                    window = TimeWindow.parse("1s"),
                    range =
                        TimeRange(
                            from = Instant.parse("2026-09-13T00:00:00Z"),
                            to = Instant.parse("2026-09-13T07:00:00Z"),
                        ),
                ).toList()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Sub-minute warmup for XAUUSDm spans 420 minutes")
    }

    @Test
    fun `non-native minute windows are rebuilt from M1 on the epoch grid (#1133)`() {
        val server = MockWebServer().apply { start() }
        try {
            val rows =
                (0 until 5).joinToString(",") { i ->
                    """{"open":${100 + i},"high":${200 + i},"low":${50 + i},"close":${150 + i},""" +
                        """"tick_volume":1,"time":"2026-07-13T08:0$i:00"}"""
                }
            server.enqueue(MockResponse().setBody("[$rows]"))
            val fetcher = Mt5BarFetcher(server.url("/").toString().trimEnd('/'))

            val candles =
                fetcher
                    .fetchRange(
                        symbol = "EURUSDm",
                        window = TimeWindow.parse("2m"),
                        range =
                            TimeRange(
                                from = Instant.parse("2026-07-13T08:00:00Z"),
                                to = Instant.parse("2026-07-13T08:05:00Z"),
                            ),
                    ).toList()

            assertThat(server.takeRequest().path).contains("timeframe=M1")
            // 08:00-08:02 and 08:02-08:04 close inside the range; 08:04 alone is a partial bucket.
            assertThat(candles).hasSize(2)
            assertThat(candles[0].open).isEqualByComparingTo("100")
            assertThat(candles[0].close).isEqualByComparingTo("151")
            assertThat(candles[0].high).isEqualByComparingTo("201")
            assertThat(candles[0].volume).isEqualByComparingTo("2")
            assertThat(candles[1].startTime).isEqualTo(Instant.parse("2026-07-13T08:02:00Z").toEpochMilli())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `windows that fit no native source still fail with the timeframe named`() {
        val fetcher = Mt5BarFetcher("http://unused")
        assertThatThrownBy {
            fetcher
                .fetchRange(
                    symbol = "XAUUSDm",
                    window = TimeWindow(90_000L),
                    range =
                        TimeRange(
                            from = Instant.parse("2026-07-13T08:00:00Z"),
                            to = Instant.parse("2026-07-13T09:00:00Z"),
                        ),
                ).toList()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Cannot align 90000ms bars")
    }
}
