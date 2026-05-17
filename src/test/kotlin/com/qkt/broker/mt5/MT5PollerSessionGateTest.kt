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
                calendar = closedCalendar(),
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
                calendar = openCalendar(),
            )
        poller.tickForTesting()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `pending poller with null calendar always calls client backward-compat`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val poller =
            MT5PendingOrderPoller(
                client = client,
                profile = profile,
                clock = FixedClock(closedSaturdayUtc),
                calendar = null,
            )
        poller.tickForTesting()
        assertThat(server.requestCount).isEqualTo(1)
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
