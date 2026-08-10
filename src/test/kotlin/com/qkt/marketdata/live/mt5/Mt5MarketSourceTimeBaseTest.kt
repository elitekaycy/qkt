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

    private fun source(
        server: MockWebServer,
        range: TimeRange = RANGE,
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
            )
        return Mt5MarketSource(
            profile = profile,
            clock = FixedClock(range.to.toEpochMilli()),
            symbolCalendars = cryptoCalendars,
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

    companion object {
        private val WINDOW = TimeWindow.parse("5m")
        private val RANGE =
            TimeRange(
                Instant.parse("2026-07-15T08:00:00Z"),
                Instant.parse("2026-07-15T08:05:00Z"),
            )
    }
}
