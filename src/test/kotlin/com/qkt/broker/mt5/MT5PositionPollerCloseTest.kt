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
    fun `same-ticket volume reduction emits an attributed partial close`() {
        server.enqueue(MockResponse().setBody(positionJson(7001L, "0.10", "90", "110")))
        server.enqueue(MockResponse().setBody(positionJson(7001L, "0.04", "90", "110")))
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":22,"entry":1,"price":"95","volume":"0.06","commission":"-0.30"}]""",
            ),
        )
        val meta = mapOf(7001L to ClosedPositionMeta("entry-1", "alpha"))
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = meta::get,
            )

        poller.tick()
        poller.tick()

        assertThat(fills).hasSize(1)
        assertThat(fills.single().quantity).isEqualByComparingTo("0.06")
        assertThat(fills.single().price).isEqualByComparingTo("95")
        assertThat(fills.single().clientOrderId).isEqualTo("entry-1")
        assertThat(fills.single().strategyId).isEqualTo("alpha")
    }

    @Test
    fun `repeated partial closes use only each newly observed closing deal for price`() {
        server.enqueue(MockResponse().setBody(positionJson(7001L, "0.10", "90", "110")))
        server.enqueue(MockResponse().setBody(positionJson(7001L, "0.07", "90", "110")))
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":22,"entry":1,"price":"95","volume":"0.03","commission":"-0.15"}]""",
            ),
        )
        server.enqueue(MockResponse().setBody(positionJson(7001L, "0.04", "90", "110")))
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":22,"entry":1,"price":"95","volume":"0.03","commission":"-0.15"},
                    {"ticket":23,"entry":1,"price":"97","volume":"0.03","commission":"-0.15"}]""",
            ),
        )
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = { ClosedPositionMeta("entry-1", "alpha") },
            )

        repeat(3) { poller.tick() }

        assertThat(fills.map { it.quantity }).containsExactly(BigDecimal("0.03"), BigDecimal("0.03"))
        assertThat(fills.map { it.price }).containsExactly(BigDecimal("95"), BigDecimal("97"))
    }

    @Test
    fun `same-ticket stop removal emits a protection drift event`() {
        server.enqueue(MockResponse().setBody(positionJson(7001L, "0.10", "90", "110")))
        server.enqueue(MockResponse().setBody(positionJson(7001L, "0.10", "0", "110")))
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = { ClosedPositionMeta("entry-1", "alpha") },
            )

        poller.tick()
        poller.tick()

        assertThat(protectionChanges).hasSize(1)
        assertThat(protectionChanges.single().ticket).isEqualTo("7001")
        assertThat(protectionChanges.single().oldStopLoss).isEqualByComparingTo("90")
        assertThat(protectionChanges.single().newStopLoss).isEqualByComparingTo("0")
        assertThat(protectionChanges.single().strategyId).isEqualTo("alpha")
    }

    @Test
    fun `engine-requested protection change does not emit drift`() {
        server.enqueue(MockResponse().setBody(positionJson(7001L, "0.10", "90", "110")))
        server.enqueue(MockResponse().setBody(positionJson(7001L, "0.10", "95", "110")))
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                isExpectedProtectionChange = { true },
            )

        poller.tick()
        poller.tick()

        assertThat(protectionChanges).isEmpty()
    }

    @Test
    fun `engine-confirmed partial close is not published twice`() {
        server.enqueue(MockResponse().setBody(positionJson(7001L, "0.10", "90", "110")))
        server.enqueue(MockResponse().setBody(positionJson(7001L, "0.04", "90", "110")))
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                engineCloseState = { EngineCloseState.CONFIRMED },
            )

        poller.tick()
        poller.tick()

        assertThat(fills).isEmpty()
    }

    @Test
    fun `close with meta lookup emits strategyId and real client-order id`() {
        // Snapshot 1: ticket 7001 (BUY) open. Snapshot 2: empty (closed). The close
        // then asks /history_deals_get — no closing deal recorded, so the price falls
        // back to the provider's last tick.
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(7001L, 0, "1.1000")))))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))
        server.enqueue(MockResponse().setBody("[]"))

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
    fun `gateway outage does not read as all positions closed`() {
        // Snapshot 1: ticket open. Snapshots 2-4: gateway down (HTTP 500). Snapshot 5:
        // gateway back, position still open. No phantom close may be synthesized, and
        // the unreachable hook fires once at the third consecutive failure.
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(7001L, 0, "1.1000")))))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("boom")) }
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(7001L, 0, "1.1000")))))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))

        val unreachable = mutableListOf<Int>()
        val recovered = mutableListOf<Int>()
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = { ClosedPositionMeta("ord", "alpha") },
                priceProvider = MarketPriceTracker().apply { update("TEST-MT5:XAUUSD", BigDecimal("1.1200")) },
                onGatewayUnreachable = { unreachable.add(it) },
                onGatewayRecovered = { recovered.add(it) },
            )
        poller.tick() // sees 7001 open
        repeat(3) { poller.tick() } // outage: diffs suspended
        assertThat(fills).isEmpty()
        assertThat(unreachable).containsExactly(3)

        poller.tick() // recovered, 7001 still open — still no close
        assertThat(fills).isEmpty()
        assertThat(recovered).containsExactly(3)

        server.enqueue(MockResponse().setBody("[]")) // deals lookup for the close
        poller.tick() // 7001 genuinely closed now
        assertThat(fills).hasSize(1)
    }

    @Test
    fun `venue-side close is priced from the closing deal, not the engine's last tick`() {
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(7001L, 0, "1.1000")))))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))
        // The venue's SL filled at 1.0950 — two partial out-deals volume-weight to it.
        // Commission and swap are reported "added to profit" (negative = charge).
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":1,"entry":0,"price":"1.1000","volume":"0.10","commission":"-0.70"},
                    {"ticket":2,"entry":1,"price":"1.0940","volume":"0.05","commission":"-0.35","swap":"-0.55"},
                    {"ticket":3,"entry":1,"price":"1.0960","volume":"0.05","commission":"-0.35","swap":"-0.55"}]""",
            ),
        )

        val priceTracker = MarketPriceTracker().apply { update("TEST-MT5:XAUUSD", BigDecimal("1.1200")) }
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = { ClosedPositionMeta("ord-1", "alpha") },
                priceProvider = priceTracker,
            )
        poller.tick()
        poller.tick()

        assertThat(fills).hasSize(1)
        // (1.0940 * 0.05 + 1.0960 * 0.05) / 0.10 = 1.0950 — the deal truth, not 1.1200.
        assertThat(fills.single().price).isEqualByComparingTo("1.0950")
        // Costs sum over all the position's deals: 0.70 + 0.35 + 0.35 commission + 1.10 swap.
        assertThat(fills.single().venueCosts).isEqualByComparingTo("2.50")
    }

    @Test
    fun `poller skips a close the engine already published`() {
        // qkt closed the ticket itself (close-by-ticket) and already emitted the fill; the
        // poller seeing the ticket gone must NOT publish a second, duplicate close.
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(7001L, 0, "1.1000")))))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))

        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = { ClosedPositionMeta("ord", "alpha") },
                engineCloseState = {
                    if (it == 7001L) EngineCloseState.CONFIRMED else EngineCloseState.NONE
                },
                priceProvider = MarketPriceTracker().apply { update("TEST-MT5:XAUUSD", BigDecimal("1.1200")) },
            )
        poller.tick() // observes 7001 open
        poller.tick() // observes 7001 gone — but the engine already published this close

        assertThat(fills).isEmpty()
    }

    @Test
    fun `poller defers a provisional engine close and publishes it after rejection`() {
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(7001L, 0, "1.1000")))))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))
        server.enqueue(MockResponse().setBody("[]"))
        var closeState = EngineCloseState.PENDING
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = { ClosedPositionMeta("ord", "alpha") },
                engineCloseState = { closeState },
                priceProvider = MarketPriceTracker().apply { update("TEST-MT5:XAUUSD", BigDecimal("1.1200")) },
            )

        poller.tick()
        poller.tick()
        assertThat(fills).isEmpty()

        closeState = EngineCloseState.NONE
        poller.tick()

        assertThat(fills).hasSize(1)
        assertThat(fills.single().clientOrderId).isEqualTo("ord")
        assertThat(fills.single().strategyId).isEqualTo("alpha")
    }

    @Test
    fun `close with no meta lookup falls back to synthetic id and blank strategyId`() {
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(8888L, 1, "2050.00")))))
        server.enqueue(MockResponse().setBody(positionsJson(emptyList())))
        server.enqueue(MockResponse().setBody("[]")) // deals lookup for the close

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
    fun `re-observed close after a snapshot flicker emits one attributed close, not a blank-strategy duplicate`() {
        // Prod 2026-06-05 (v0.29.14): ticket 2814861313 (dsl-hedge_straddle--10) closed,
        // then a later /positions snapshot briefly re-surfaced it before it vanished again.
        // The poller re-diffed that flicker as a fresh open→close. The qkt-side meta had
        // been consumed on the first lookup, so the second close carried a blank strategyId
        // and an "opened outside this session" id — inflating the trade count and breaking
        // per-strategy PnL. The poller must never re-report a ticket it already closed.
        val t = 2814861313L
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(t, 0, "2000.00"))))) // open
        server.enqueue(MockResponse().setBody(positionsJson(emptyList()))) // closed
        server.enqueue(MockResponse().setBody(positionsJson(listOf(Triple(t, 0, "2000.00"))))) // flicker back
        server.enqueue(MockResponse().setBody(positionsJson(emptyList()))) // gone again

        // Mimic the real MT5Broker.lookupClosedTicketMeta contract: meta is consumed on first read.
        val meta = mutableMapOf(t to ClosedPositionMeta("dsl-hedge_straddle--10", "hedge_straddle"))
        val poller =
            MT5PositionPoller(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = bus,
                clock = clock,
                closedTicketMeta = { meta.remove(it) },
                priceProvider = MarketPriceTracker().apply { update("TEST-MT5:XAUUSD", BigDecimal("2000.00")) },
            )
        poller.tick() // open
        poller.tick() // close → exactly one attributed event
        poller.tick() // flicker re-surfaces the closed ticket
        poller.tick() // it disappears again → must NOT emit a second (blank) close

        assertThat(fills).hasSize(1)
        val e = fills.single()
        assertThat(e.strategyId).isEqualTo("hedge_straddle")
        assertThat(e.clientOrderId).isEqualTo("dsl-hedge_straddle--10")
        assertThat(e.brokerOrderId).isEqualTo(t.toString())
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
