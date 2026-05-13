package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase 27 R1/R2/R3 fix: the position poller, on detecting a venue-side close, must
 * publish [BrokerEvent.OrderFilled] with the qkt-side strategyId + clientOrderId
 * resolved via the meta lookup, and with a real close price from a [MarketPriceProvider]
 * rather than the (stale) `priceOpen` from the last snapshot.
 */
class MT5PositionPollerCloseTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MT5Client
    private lateinit var bus: EventBus
    private lateinit var clock: FixedClock
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()

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

    private fun positionsJson(positions: List<Triple<Long, Int, String>>): String =
        positions.joinToString(prefix = "[", postfix = "]") { (ticket, type, priceOpen) ->
            """{"ticket":"$ticket","symbol":"XAUUSD","type":"$type","volume":"0.10","price_open":"$priceOpen","sl":"0","tp":"0","profit":"0","magic":"12345","open_time":"0"}"""
        }

    @Test
    fun `close with meta lookup emits strategyId and real client-order id`() {
        // Snapshot 1: ticket 7001 (BUY) open. Snapshot 2: empty (closed).
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(7001L, 0, "1.1000")))))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))

        val registry = mapOf(7001L to ClosedPositionMeta("dsl-strat-42", "alpha"))
        val priceTracker = MarketPriceTracker().apply { update("TEST-MT5:XAUUSD", BigDecimal("1.1200")) }

        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = registry::get,
                priceProvider = priceTracker,
            )
        poller.tick() // observes ticket 7001 as opened, no close event yet
        assertThat(fills).isEmpty()
        poller.tick() // observes 7001 as closed → emits close event

        assertThat(fills).hasSize(1)
        val e = fills.single()
        assertThat(e.clientOrderId).isEqualTo("dsl-strat-42")
        assertThat(e.strategyId).isEqualTo("alpha")
        assertThat(e.brokerOrderId).isEqualTo("7001")
        assertThat(e.symbol).isEqualTo("TEST-MT5:XAUUSD")
        // type=0 (BUY) → close side is SELL
        assertThat(e.side).isEqualTo(Side.SELL)
        // Price comes from the priceProvider, NOT priceOpen
        assertThat(e.price).isEqualByComparingTo("1.1200")
        assertThat(e.quantity).isEqualByComparingTo("0.10")
    }

    @Test
    fun `close with no meta lookup falls back to synthetic id and blank strategyId`() {
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(8888L, 1, "2050.00")))))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))

        // Empty registry — position was opened outside this qkt session
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = { null },
                priceProvider = MarketPriceTracker().apply { update("TEST-MT5:XAUUSD", BigDecimal("2040.00")) },
            )
        poller.tick()
        poller.tick()

        assertThat(fills).hasSize(1)
        val e = fills.single()
        // Synthetic id (back-compat path)
        assertThat(e.clientOrderId).isEqualTo("mt5-close-8888")
        assertThat(e.strategyId).isEqualTo("")
        // Real price from provider still used
        assertThat(e.price).isEqualByComparingTo("2040.00")
        // type=1 (SELL) → close side is BUY
        assertThat(e.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `close with no price provider falls back to priceOpen`() {
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(9999L, 0, "1.0500")))))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))

        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = { ClosedPositionMeta("ord-x", "beta") },
                priceProvider = null,
            )
        poller.tick()
        poller.tick()

        assertThat(fills).hasSize(1)
        // No provider → fall back to priceOpen
        assertThat(fills.single().price).isEqualByComparingTo("1.0500")
        // Meta still resolved
        assertThat(fills.single().strategyId).isEqualTo("beta")
        assertThat(fills.single().clientOrderId).isEqualTo("ord-x")
    }

    @Test
    fun `provider with no tick on the symbol falls back to priceOpen`() {
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(123L, 0, "1.2000")))))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))

        val emptyProvider =
            object : MarketPriceProvider {
                override fun lastPrice(symbol: String): BigDecimal? = null
            }
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = { ClosedPositionMeta("o", "s") },
                priceProvider = emptyProvider,
            )
        poller.tick()
        poller.tick()
        assertThat(fills.single().price).isEqualByComparingTo("1.2000")
    }
}
