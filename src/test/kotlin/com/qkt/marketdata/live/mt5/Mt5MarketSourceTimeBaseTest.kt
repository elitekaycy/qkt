package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.broker.mt5.MT5ServerTimeZone
import com.qkt.broker.mt5.SymbolCalendars
import com.qkt.broker.mt5.SymbolPolicy
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import java.time.Instant
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
    ): Mt5MarketSource {
        val cryptoCalendars = SymbolCalendars(emptyList(), TradingCalendar.crypto())
        val profile =
            MT5BrokerProfile(
                name = "test",
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                symbolPolicy = SymbolPolicy(),
                serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
                magic = 10,
                symbolCalendars = cryptoCalendars,
                retryAttempts = retryAttempts,
            )
        return Mt5MarketSource(
            profile = profile,
            clock = FixedClock(range.to.toEpochMilli()),
            symbolCalendars = cryptoCalendars,
            retryBackoffMs = 0L,
        )
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
