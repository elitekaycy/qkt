package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A buy limit whose placement reply was lost, found resting at the venue. */
class MT5UnknownPlacementResolutionTest {
    private val sentAt = 1_800_000_000_000L
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(time = sentAt + 1_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val mt5Symbol = MT5Symbol(profile.symbolPolicy)
    private val client = MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0)
    private val scheduler = MT5UnknownResolveScheduler(profile.name, periodicResolveMs = 60_000L)
    private val events = mutableListOf<BrokerEvent>()
    private val resolution =
        MT5UnknownPlacementResolution(
            profile,
            client,
            bus,
            clock,
            mt5Symbol,
            books,
            MT5BrokerEvents(profile, bus, clock),
            scheduler,
            MT5PendingFills(profile, bus, clock, mt5Symbol, books, MT5PartialEntries(books, bus, clock)),
            MT5UnknownVenueMatcher(books),
            MT5UnknownDealReplay(profile, client, bus, clock, books),
            unknownResolveBackoffMs = 0L,
        )
    private val request =
        OrderRequest.Limit(
            id = "dsl-gold--7",
            symbol = "EXNESS:XAUUSD",
            side = Side.BUY,
            quantity = BigDecimal("0.10"),
            limitPrice = BigDecimal("2390"),
            timeInForce = TimeInForce.GTC,
            timestamp = sentAt,
            strategyId = "gold",
        )
    private val placement =
        MT5OrderRequest(
            symbol = "XAUUSDm",
            volume = BigDecimal("0.10"),
            type = "BUY_LIMIT",
            price = BigDecimal("2390"),
            magic = 10001,
            comment = "dsl-gold--7",
            clientOrderId = "cid-7",
        )

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderFilled> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderRejected> { events.add(it) }
    }

    @AfterEach
    fun stop() = gateway.close()

    private fun resting(
        ticket: Long,
        clientOrderId: String,
        symbol: String = "XAUUSDm",
    ) = """{"ticket":$ticket,"symbol":"$symbol","type_str":"BUY_LIMIT","volume_current":"0.10",""" +
        """"price_open":"2390","magic":10001,"comment":"dsl-gold--7","client_order_id":"$clientOrderId"}"""

    @Test
    fun `a resting order carrying the placement id is accepted under its ticket`() {
        gateway.on(
            "/orders",
            listOf(resting(0, "cid-7"), resting(9100, "other", symbol = "EURUSDm"), resting(9101, "cid-7"))
                .joinToString(",", "[", "]"),
        )
        gateway.on("/get_positions", "[]")

        resolution.resolveUnknownOutcome(request, placement, sentAt, null, "timeout")

        val accepted = events.single() as BrokerEvent.OrderAccepted
        assertThat(accepted.clientOrderId).isEqualTo("dsl-gold--7")
        assertThat(accepted.brokerOrderId).isEqualTo("9101")
        assertThat(books.pendingBook.ticketOf("dsl-gold--7")).isEqualTo(9101L)
        assertThat(books.pendingBook.meta(9101L)?.requestedQuantity).isEqualByComparingTo("0.10")
        assertThat(gateway.calls("/history_deals_get")).isEmpty()
    }

    @Test
    fun `a resting order the broker already owns is not this order's outcome`() {
        books.pendingBook.register(9101L, MT5TicketMeta("someone-else", "gold"))
        gateway.on("/orders", "[" + resting(9101, "cid-7") + "]")
        gateway.on("/get_positions", "[]")
        gateway.on("/history_deals_get", "[]")

        resolution.resolveUnknownOutcome(request, placement, sentAt, null, "timeout")

        val rejected = events.single() as BrokerEvent.OrderRejected
        assertThat(rejected.reason).contains("resolved as not placed").contains("timeout")
        assertThat(gateway.calls("/history_deals_get")).hasSize(MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS)
    }
}
