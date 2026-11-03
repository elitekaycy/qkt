package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** A poller started over tickets already open at the venue (a restart over held positions). */
class MT5PositionPollerRestartTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MT5Client
    private lateinit var bus: EventBus
    private lateinit var clock: FixedClock
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()
    private val protectionChanges = mutableListOf<BrokerEvent.PositionProtectionChanged>()

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
        clock = FixedClock(time = 1_700_000_000_000L)
        bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        bus.subscribe<BrokerEvent.PositionProtectionChanged> { e -> protectionChanges.add(e) }
        client =
            MT5Client(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                serverTimeZone = MT5ServerTimeZone.UTC,
                httpTimeoutMs = 2000,
                retryAttempts = 0,
            )
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun positionsJson(positions: List<Triple<Long, Int, String>>): String =
        positions.joinToString(prefix = "[", postfix = "]") { (ticket, type, priceOpen) ->
            """{"ticket":"$ticket","symbol":"XAUUSD","type":"$type","volume":"0.10","price_open":"$priceOpen","sl":"0","tp":"0","profit":"0","magic":"12345","time_msc":"0"}"""
        }

    private fun positionJson(
        ticket: Long,
        volume: String,
        stopLoss: String,
        takeProfit: String,
    ): String =
        """[{"ticket":"$ticket","symbol":"XAUUSD","type":"0","volume":"$volume","price_open":"100","sl":"$stopLoss","tp":"$takeProfit","profit":"0","magic":"12345","time_msc":"0"}]"""

    @Test
    fun `a sibling session's ticket held across a restart closes without an unattributed fill`() {
        // Rehearsal 2026-11-03: two strategies on one magic, restarted while each held a ticket.
        // Each broker's poller started with both tickets in its first snapshot; the sibling's
        // close then reached this session with a blank owner and was dropped as unattributed.
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(9001L, 0, "2050.00")))))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                onPositionOpened = { false },
                closedTicketMeta = { null },
            )
        try {
            poller.start()
            poller.markForeign(9001L)
            poller.tick()
        } finally {
            poller.stop()
        }

        assertThat(fills).isEmpty()
    }

    @Test
    fun `an own ticket held across a restart still books its venue close`() {
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(9002L, 0, "2050.00")))))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))
        server.enqueue(MockResponse().setBody("[]"))
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = { ClosedPositionMeta("dsl-owner--1", "owner") },
                priceProvider = MarketPriceTracker().apply { update("TEST-MT5:XAUUSD", BigDecimal("2050.00")) },
            )
        try {
            poller.start()
            poller.tick()
        } finally {
            poller.stop()
        }

        assertThat(fills.map { it.strategyId }).containsExactly("owner")
    }

    @Test
    fun `a sibling strategy's position is reported foreign, a manual one is not`() {
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":51,"symbol":"BTCUSDm","type":0,"volume":"0.01","price_open":"77000",
                    "sl":"0","tp":"0","profit":"0","magic":10001,"time_msc":"1748513200",
                    "comment":"dsl-atto_mix_solo--0"},
                   {"ticket":52,"symbol":"BTCUSDm","type":0,"volume":"0.01","price_open":"77000",
                    "sl":"0","tp":"0","profit":"0","magic":10001,"time_msc":"1748513200","comment":""}]""",
            ),
        )
        val foreign = mutableListOf<Long>()
        MT5StateRecovery(
            client = client,
            profile = profile,
            symbol = MT5Symbol(profile.symbolPolicy),
            bus = bus,
            strategyName = "atto_mix_a",
            onSiblingPosition = { foreign.add(it) },
        ).recover()

        assertThat(foreign).containsExactly(51L)
    }
}
