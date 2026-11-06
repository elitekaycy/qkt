package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Restored orders joined back to the venue after a restart: E.g. an OCO with one leg resting, one filled. */
class MT5RestartRecoveryTest {
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(time = 1_800_000_000_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val mt5Symbol = MT5Symbol(profile.symbolPolicy)
    private val partials = MT5PartialEntries(books, bus, clock)
    private val protection = MT5RequestedProtection(MT5OrderTranslator(profile, mt5Symbol))
    private val seeded = mutableListOf<Set<Long>>()
    private val events = mutableListOf<BrokerEvent>()
    private val recovery =
        MT5RestartRecovery(
            profile,
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            bus,
            clock,
            books,
            MT5PendingFills(profile, bus, clock, mt5Symbol, books, partials),
            protection,
            MT5PartialEntryRecovery(profile, bus, clock, books, partials, protection, seedTrackedTickets = {}),
            seedTrackedTickets = { seeded.add(it) },
            recoveryReadAttempts = 1,
            recoveryReadBackoffMs = 1L,
        )

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderFilled> { events.add(it) }
    }

    @AfterEach
    fun stop() = gateway.close()

    private fun stopLeg(
        id: String,
        ticket: String?,
        side: Side = Side.BUY,
    ) = ManagedOrder(
        id = id,
        request =
            OrderRequest.Stop(
                id = id,
                symbol = "EXNESS:XAUUSD",
                side = side,
                quantity = BigDecimal("0.10"),
                stopPrice = BigDecimal("2450"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "gold",
            ),
        state = OrderState.WORKING,
        brokerOrderId = ticket,
        createdAt = 0L,
        lastUpdatedAt = 0L,
    )

    private fun venue(
        pending: String,
        positions: String,
    ) {
        gateway.on("/orders", pending)
        gateway.on("/get_positions", positions)
    }

    @Test
    fun `nothing to recover reads nothing`() {
        assertThat(recovery.recoverPendingOrders(emptyList(), emptySet())).isEmpty()
        assertThat(gateway.requests).isEmpty()
    }

    @Test
    fun `a leg still resting is re-registered under its ticket without any event`() {
        venue(
            """[{"ticket":9001,"symbol":"XAUUSDm","type":"4","volume":"0.10","price_open":"2450","magic":10001}]""",
            "[]",
        )

        val accounted = recovery.recoverPendingOrders(listOf(stopLeg("leg-a", "9001")), emptySet())

        assertThat(accounted).containsExactly("leg-a")
        assertThat(books.pendingBook.ticketOf("leg-a")).isEqualTo(9001L)
        assertThat(books.pendingBook.meta(9001L)?.requestedQuantity).isEqualByComparingTo("0.10")
        assertThat(events).isEmpty()
        assertThat(seeded.single()).isEmpty()
    }

    @Test
    fun `a leg with no remembered ticket is joined to the resting order carrying its id`() {
        venue(
            """[{"ticket":9001,"symbol":"XAUUSDm","type":"4","volume":"0.10","price_open":"2450","magic":10001,""" +
                """"client_order_id":"leg-a"}]""",
            "[]",
        )

        assertThat(recovery.recoverPendingOrders(listOf(stopLeg("leg-a", null)), emptySet())).containsExactly("leg-a")
        assertThat(books.pendingBook.ticketOf("leg-a")).isEqualTo(9001L)
    }

    @Test
    fun `a leg that filled during downtime already booked by the ledger is only re-tracked and acknowledged`() {
        venue("[]", "[" + Mt5RoutedGateway.position(9002, "0.10", priceOpen = "2450") + "]")

        val accounted = recovery.recoverPendingOrders(listOf(stopLeg("leg-b", "9002")), setOf("9002"))

        assertThat(accounted).containsExactly("leg-b")
        val accepted = events.single() as BrokerEvent.OrderAccepted
        assertThat(accepted.clientOrderId).isEqualTo("leg-b")
        assertThat(accepted.brokerOrderId).isEqualTo("9002")
        assertThat(books.positionBook.meta(9002L)?.orderId).isEqualTo("leg-b")
        assertThat(books.positionBook.symbol(9002L)).isEqualTo("EXNESS:XAUUSD")
    }

    @Test
    fun `a leg that filled during downtime and was never booked publishes its fill`() {
        venue("[]", "[" + Mt5RoutedGateway.position(9002, "0.10", priceOpen = "2450") + "]")

        recovery.recoverPendingOrders(listOf(stopLeg("leg-b", "9002")), emptySet())

        val fill = events.filterIsInstance<BrokerEvent.OrderFilled>().single()
        assertThat(fill.clientOrderId).isEqualTo("leg-b")
        assertThat(fill.price).isEqualByComparingTo("2450")
    }

    @Test
    fun `a remembered ticket the venue no longer shows is tracked as vanished`() {
        venue("[]", "[]")

        val accounted = recovery.recoverPendingOrders(listOf(stopLeg("leg-c", "9003")), emptySet())

        assertThat(accounted).containsExactly("leg-c")
        assertThat(seeded.single()).containsExactly(9003L)
        assertThat(books.pendingBook.ticketOf("leg-c")).isEqualTo(9003L)
    }

    @Test
    fun `a leg with no remembered ticket is joined to the one resting order its truncated comment fits`() {
        venue(
            """[{"ticket":9004,"symbol":"XAUUSDm","type":"4","volume":"0.10","price_open":"2450","magic":10001,""" +
                """"comment":"leg-a-long-strat"}]""",
            "[]",
        )

        val accounted = recovery.recoverPendingOrders(listOf(stopLeg("leg-a-long-strategy--7", null)), emptySet())

        assertThat(accounted).containsExactly("leg-a-long-strategy--7")
        assertThat(books.pendingBook.ticketOf("leg-a-long-strategy--7")).isEqualTo(9004L)
    }

    @Test
    fun `an order with no venue counterpart is left for the caller to retire`() {
        venue("[]", "[]")

        assertThat(recovery.recoverPendingOrders(listOf(stopLeg("leg-d", null)), emptySet())).isEmpty()
    }
}
