package com.qkt.broker.mt5

import com.qkt.common.FixedClock
import com.qkt.common.SessionAnchor
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import java.time.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies the pending-order poller skips its `/orders` HTTP call when the trading
 * calendar reports out-of-session. Prevents gateway log spam on weekends and saves
 * round-trips when nothing can change.
 */
class MT5PollerSessionGateTest {
    private val openSundayUtc = Instant.parse("2026-05-17T22:00:00Z").toEpochMilli()
    private val closedSaturdayUtc = Instant.parse("2026-05-16T12:00:00Z").toEpochMilli()

    private lateinit var server: MockWebServer
    private lateinit var client: MT5Client

    private val profile =
        MT5BrokerProfile(
            name = "test-mt5",
            gatewayUrl = "placeholder",
            symbolPolicy = SymbolPolicy(),
            magic = 12345,
            httpTimeoutMs = 2000,
            retryAttempts = 0,
            pollIntervalMs = 100_000,
        )

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client =
            MT5Client(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                tzOffsetHours = 0,
                httpTimeoutMs = 2000,
                retryAttempts = 0,
            )
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `pending poller skips client call when calendar reports out-of-session`() {
        val poller =
            MT5PendingOrderPoller(
                client = client,
                profile = profile,
                clock = FixedClock(closedSaturdayUtc),
                sessionGate = { false },
            )
        poller.tickForTesting()
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `pending poller calls client when calendar reports in-session`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val poller =
            MT5PendingOrderPoller(
                client = client,
                profile = profile,
                clock = FixedClock(openSundayUtc),
                sessionGate = { true },
            )
        poller.tickForTesting()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `pending poller outage does not read as all pendings cancelled`() {
        // A tracked pending + a failed read: the diff must be skipped, not treated as
        // "every pending cancelled" (which would dismantle OCO/bracket protection).
        val pendingJson =
            """[{"ticket":"9001","symbol":"XAUUSD","type":"ORDER_TYPE_BUY_LIMIT","volume":"0.1",
            "price_open":"2000.0","sl":"0","tp":"0","magic":"12345","time_setup":"0","time_expiration":"0"}]"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("boom")) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val disappeared = mutableListOf<Long>()
        val unreachable = mutableListOf<Int>()
        val poller =
            MT5PendingOrderPoller(
                client = client,
                profile = profile,
                clock = FixedClock(openSundayUtc),
                sessionGate = null,
                onPendingDisappeared = { disappeared.add(it) },
                onGatewayUnreachable = { unreachable.add(it) },
            )
        poller.tickForTesting() // sees 9001 pending
        repeat(3) { poller.tickForTesting() } // outage
        assertThat(disappeared).isEmpty()
        assertThat(unreachable).containsExactly(3)

        poller.tickForTesting() // clean read, genuinely gone now
        assertThat(disappeared).containsExactly(9001L)
    }

    @Test
    fun `pending poller with null gate always calls client backward-compat`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val poller =
            MT5PendingOrderPoller(
                client = client,
                profile = profile,
                clock = FixedClock(closedSaturdayUtc),
                sessionGate = null,
            )
        poller.tickForTesting()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `pending poller polls when any configured calendar is open multi-asset`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val sc =
            SymbolCalendars(
                listOf(
                    SymbolCalendars.Rule("CLOSED*", closedCalendar()),
                    SymbolCalendars.Rule("OPEN*", openCalendar()),
                ),
                default = closedCalendar(),
            )
        val poller =
            MT5PendingOrderPoller(
                client = client,
                profile = profile.copy(symbolCalendars = sc),
                clock = FixedClock(closedSaturdayUtc),
                sessionGate = sc::anyCalendarInSession,
            )
        poller.tickForTesting()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `pending poller skips when all configured calendars closed multi-asset`() {
        val sc = SymbolCalendars(emptyList(), default = closedCalendar())
        val poller =
            MT5PendingOrderPoller(
                client = client,
                profile = profile.copy(symbolCalendars = sc),
                clock = FixedClock(closedSaturdayUtc),
                sessionGate = sc::anyCalendarInSession,
            )
        poller.tickForTesting()
        assertThat(server.requestCount).isZero()
    }

    private fun closedCalendar(): TradingCalendar =
        object : TradingCalendar {
            override val name = "always-closed"

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

    private fun openCalendar(): TradingCalendar =
        object : TradingCalendar {
            override val name = "always-open"

            override fun isInSession(
                symbol: String,
                t: Instant,
            ): Boolean = true

            override fun sessionRange(
                symbol: String,
                t: Instant,
            ): TimeRange = TimeRange(Instant.EPOCH, Instant.MAX)

            override fun anchorEpochFor(
                anchor: SessionAnchor,
                t: Instant,
            ): Long = 0L

            override fun rangeFor(
                anchor: SessionAnchor,
                anchorEpoch: Long,
            ): TimeRange = TimeRange(Instant.EPOCH, Instant.MAX)
        }
}
