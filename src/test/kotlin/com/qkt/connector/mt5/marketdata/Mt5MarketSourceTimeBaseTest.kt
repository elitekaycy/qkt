package com.qkt.connector.mt5.marketdata

import com.qkt.candles.TimeWindow
import com.qkt.common.DailyBreakCalendar
import com.qkt.common.FixedClock
import com.qkt.common.SymbolCalendars
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.connector.mt5.MT5BrokerProfile
import com.qkt.connector.mt5.MT5ServerTimeZone
import com.qkt.connector.mt5.SymbolPolicy
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class Mt5MarketSourceTimeBaseTest {
    @Test
    fun `recent bars and ticks pass when both normalize to the same UTC clock`() {
        val server = MockWebServer().apply { start() }
        try {
            enqueueTimeResponses(server, barTime = "2026-07-15T11:00:00Z")
            val source = source(server)

            val bars = source.bars("TEST:EURUSD", WINDOW, RANGE).toList()

            assertThat(bars.single().symbol).isEqualTo("TEST:EURUSD")
            assertThat(bars.single().startTime).isEqualTo(Instant.parse("2026-07-15T08:00:00Z").toEpochMilli())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `recent bar shifted by a competing gateway offset fails closed`() {
        val server = MockWebServer().apply { start() }
        try {
            enqueueTimeResponses(server, barTime = "2026-07-15T08:00:00Z")
            val source = source(server)

            assertThatThrownBy { source.bars("TEST:EURUSD", WINDOW, RANGE) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("MT5 time-base mismatch")
                .hasMessageContaining("MT5_SERVER_UTC_OFFSET_SECONDS=0")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `recent closed bar passes when its end remains inside the freshness allowance`() {
        val server = MockWebServer().apply { start() }
        try {
            val range =
                TimeRange(
                    Instant.parse("2026-07-15T08:39:00Z"),
                    Instant.parse("2026-07-15T08:44:01.531Z"),
                )
            enqueueTimeResponses(
                server,
                barTime = "2026-07-15T11:39:00Z",
                tickTime = "2026-07-15T11:44:01.531Z",
            )
            val source = source(server, range)

            val bars = source.bars("TEST:EURUSD", TimeWindow.parse("1m"), range).toList()

            assertThat(bars.single().endTime)
                .isEqualTo(Instant.parse("2026-07-15T08:40:00Z").toEpochMilli())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty recent history retries and recovers before time-base validation`() {
        val server = MockWebServer().apply { start() }
        try {
            val tickTimeMs = Instant.parse("2026-07-15T11:05:00Z").toEpochMilli()
            server.enqueue(MockResponse().setBody("""{"point":"0.00001"}"""))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(
                MockResponse().setBody(
                    """[{"open":1,"high":1,"low":1,"close":1,"spread":0,"tick_volume":1,"time":"2026-07-15T11:00:00Z"}]""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"bid":1,"ask":1,"last":1,"flags":6,"time_msc":$tickTimeMs}""",
                ),
            )

            val bars = source(server, retryAttempts = 1).bars("TEST:EURUSD", WINDOW, RANGE).toList()

            assertThat(bars).hasSize(1)
            assertThat(server.requestCount).isEqualTo(4)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `stale recent history retries like empty history does`() {
        // A terminal repopulating its history cache serves a STALE window as readily as an empty
        // one, and when several strategies warm up together the stale shape is the common one.
        // Only the empty shape used to retry, so a deployment failed on a window that was correct
        // on the very next read: observed live when a 5m stream reported a 17-hour-stale window
        // while its 15m sibling on the same symbol succeeded.
        val server = MockWebServer()
        val barReads =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val tickTimeMs = Instant.parse("2026-07-15T11:05:00Z").toEpochMilli()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    return when {
                        path.contains("symbol_info_tick") ->
                            MockResponse().setBody("""{"bid":1,"ask":1,"last":1,"flags":6,"time_msc":$tickTimeMs}""")
                        path.contains("fetch_data") -> {
                            // First read is hours stale; the terminal has caught up by the retry.
                            val barTime =
                                if (barReads.getAndIncrement() ==
                                    0
                                ) {
                                    "2026-07-15T02:00:00Z"
                                } else {
                                    "2026-07-15T11:00:00Z"
                                }
                            MockResponse().setBody(
                                """[{"open":1,"high":1,"low":1,"close":1,"spread":0,"tick_volume":1,"time":"$barTime"}]""",
                            )
                        }
                        else -> MockResponse().setBody("""{"point":"0.00001"}""")
                    }
                }
            }
        server.start()
        try {
            val bars = source(server, retryAttempts = 1).bars("TEST:EURUSD", WINDOW, RANGE).toList()

            assertThat(bars).hasSize(1)
            assertThat(barReads.get())
                .withFailMessage("stale window must be re-read, not accepted or fatal on first look")
                .isGreaterThanOrEqualTo(2)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty recent history still fails closed after retries are exhausted`() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"point":"0.00001"}"""))
            repeat(3) { server.enqueue(MockResponse().setBody("[]")) }

            assertThatThrownBy {
                source(server, retryAttempts = 2).bars("TEST:EURUSD", WINDOW, RANGE).toList()
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("no decoded bar remained")
            assertThat(server.requestCount).isEqualTo(4)
        } finally {
            server.shutdown()
        }
    }

    private fun source(
        server: MockWebServer,
        range: TimeRange = RANGE,
        retryAttempts: Int = 0,
        calendar: TradingCalendar = TradingCalendar.crypto(),
        serverTimeZone: MT5ServerTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
    ): Mt5MarketSource {
        val calendars = SymbolCalendars(emptyList(), calendar)
        val profile =
            MT5BrokerProfile(
                name = "test",
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                symbolPolicy = SymbolPolicy(),
                serverTimeZone = serverTimeZone,
                magic = 10,
                symbolCalendars = calendars,
                retryAttempts = retryAttempts,
            )
        return Mt5MarketSource(
            profile = profile,
            clock = FixedClock(range.to.toEpochMilli()),
            symbolCalendars = calendars,
            retryBackoffMs = 0L,
        )
    }

    @Test
    fun `first tick after a weekend passes although the newest closed bar is two days old`() {
        // bot2, 2026-08-24 00:04:52Z: XAGUSD 4h — newest closed bar ended Saturday 00:00Z,
        // the first Monday tick is 48h later. Every 4h slot in between was out of session
        // except Sunday 20:00 (the FX calendar opens Sunday 22:00), so no bar could have
        // closed: one in-session slot, not a time-base offset (#1055).
        val server = MockWebServer().apply { start() }
        try {
            val range =
                TimeRange(
                    Instant.parse("2026-08-08T00:00:00Z"),
                    Instant.parse("2026-08-24T00:04:52.930Z"),
                )
            enqueueTimeResponses(
                server,
                barTime = "2026-08-21T20:00:00Z",
                tickTime = "2026-08-24T00:04:52.930Z",
            )
            val source =
                source(server, range, calendar = TradingCalendar.fxDefault(), serverTimeZone = MT5ServerTimeZone.UTC)

            val bars = source.bars("TEST:XAGUSD", TimeWindow.parse("4h"), range).toList()

            assertThat(bars.single().endTime).isEqualTo(Instant.parse("2026-08-22T00:00:00Z").toEpochMilli())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a three hour offset on a trading day still fails closed under the session-time rule`() {
        // Wednesday 11:00Z: the newest 1m bar ended at 08:00Z per the (mis-zoned) bar clock
        // while the tick reads 11:00Z. All 181 slot starts in between were in session — a real
        // server-zone offset, which must still abort the deploy.
        val server = MockWebServer().apply { start() }
        try {
            val range =
                TimeRange(
                    Instant.parse("2026-08-19T07:00:00Z"),
                    Instant.parse("2026-08-19T11:00:05Z"),
                )
            enqueueTimeResponses(
                server,
                barTime = "2026-08-19T07:59:00Z",
                tickTime = "2026-08-19T11:00:05Z",
            )
            val source =
                source(server, range, calendar = TradingCalendar.fxDefault(), serverTimeZone = MT5ServerTimeZone.UTC)

            assertThatThrownBy { source.bars("TEST:EURUSD", TimeWindow.parse("1m"), range).toList() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("inSessionSlots=181")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty history inside a scheduled pause is returned without a time-base failure`() {
        // Live 2026-09-14 21:05Z (#1144): a 5s XAUUSD stream redeployed during the Exness metals
        // pause (17:00-18:00 New York). Its 50s warmup window held no bar and the read was
        // rejected as a time-base mismatch, so the deploy failed and retried until the venue
        // reopened. The pause is modelled by the calendar; the source must hand the empty
        // window back so WarmupHistory can widen its range to the bars before the pause.
        val server = MockWebServer().apply { start() }
        try {
            // 21:05Z on a Monday in September = 17:05 New York, inside the pause and in session.
            val range =
                TimeRange(
                    Instant.parse("2026-09-14T21:03:00Z"),
                    Instant.parse("2026-09-14T21:05:00Z"),
                )
            val pausedMetals =
                DailyBreakCalendar(
                    TradingCalendar.fxDefault(),
                    LocalTime.of(17, 0),
                    LocalTime.of(18, 0),
                    ZoneId.of("America/New_York"),
                )
            server.enqueue(MockResponse().setBody("""{"point":"0.001"}"""))
            // Enough empty windows for the unfixed retry loop to exhaust and fail closed with
            // "no decoded bar remained"; the fixed source consumes exactly one.
            repeat(3) { server.enqueue(MockResponse().setBody("[]")) }
            val source =
                source(
                    server,
                    range,
                    retryAttempts = 2,
                    calendar = pausedMetals,
                    serverTimeZone = MT5ServerTimeZone.UTC,
                )

            val bars = source.bars("TEST:XAUUSD", TimeWindow.parse("1m"), range).toList()

            assertThat(bars).isEmpty()
            // No time-base retry loop either: one point read plus one bar read.
            assertThat(server.requestCount).isEqualTo(2)
        } finally {
            server.shutdown()
        }
    }

    private fun enqueueTimeResponses(
        server: MockWebServer,
        barTime: String,
        tickTime: String = "2026-07-15T11:05:00Z",
    ) {
        val serverWallTickMs = Instant.parse(tickTime).toEpochMilli()
        server.enqueue(MockResponse().setBody("""{"point":"0.00001"}"""))
        server.enqueue(
            MockResponse().setBody(
                """[{"open":1,"high":1,"low":1,"close":1,"spread":0,"tick_volume":1,"time":"$barTime"}]""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"bid":1,"ask":1,"last":1,"flags":6,"time_msc":$serverWallTickMs}""",
            ),
        )
    }

    @Test
    fun `tick just before a freshly closed bar end passes as a boundary race`() {
        val server = MockWebServer().apply { start() }
        try {
            val range =
                TimeRange(
                    Instant.parse("2026-07-15T08:39:00Z"),
                    Instant.parse("2026-07-15T08:44:01.531Z"),
                )
            // Bar closes at the minute boundary (…:40:00Z); the newest tick for a thin
            // symbol printed 389ms earlier (…:39:59.611Z). This is a legitimate boundary
            // race, not a multi-hour offset, so the deploy must proceed.
            enqueueTimeResponses(
                server,
                barTime = "2026-07-15T11:39:00Z",
                tickTime = "2026-07-15T11:39:59.611Z",
            )
            val source = source(server, range)

            val bars = source.bars("TEST:EURUSD", TimeWindow.parse("1m"), range).toList()

            assertThat(bars.single().endTime)
                .isEqualTo(Instant.parse("2026-07-15T08:40:00Z").toEpochMilli())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `tick a full window before the bar end still fails closed`() {
        val server = MockWebServer().apply { start() }
        try {
            val range =
                TimeRange(
                    Instant.parse("2026-07-15T08:39:00Z"),
                    Instant.parse("2026-07-15T08:44:01.531Z"),
                )
            // Tick is 61s behind the bar end — beyond one 1m window. That is a real
            // time-base disagreement, not a boundary race, and must fail closed.
            enqueueTimeResponses(
                server,
                barTime = "2026-07-15T11:39:00Z",
                tickTime = "2026-07-15T11:38:59.000Z",
            )
            val source = source(server, range)

            assertThatThrownBy { source.bars("TEST:EURUSD", TimeWindow.parse("1m"), range) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("MT5 time-base mismatch")
        } finally {
            server.shutdown()
        }
    }

    companion object {
        private val WINDOW = TimeWindow.parse("5m")
        private val RANGE =
            TimeRange(
                Instant.parse("2026-07-15T08:00:00Z"),
                Instant.parse("2026-07-15T08:05:00Z"),
            )
    }
}
