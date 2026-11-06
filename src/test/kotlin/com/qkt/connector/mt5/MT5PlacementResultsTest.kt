package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A market buy of 1.00 XAUUSD and the venue's reply to it. */
class MT5PlacementResultsTest {
    private val sentAt = 1_800_000_000_000L
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(time = sentAt + 1_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val client = MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0)
    private val mt5Symbol = MT5Symbol(profile.symbolPolicy)
    private val brokerEvents = MT5BrokerEvents(profile, bus, clock)
    private val scheduler = MT5UnknownResolveScheduler(profile.name, periodicResolveMs = 3_600_000L)
    private val partials = MT5PartialEntries(books, bus, clock)
    private val pendingFills = MT5PendingFills(profile, bus, clock, mt5Symbol, books, partials)
    private val results =
        MT5PlacementResults(
            bus,
            clock,
            books,
            brokerEvents,
            scheduler,
            pendingFills,
            MT5UnknownPlacementResolution(
                profile,
                client,
                bus,
                clock,
                mt5Symbol,
                books,
                brokerEvents,
                scheduler,
                pendingFills,
                MT5UnknownVenueMatcher(books),
                MT5UnknownDealReplay(profile, client, bus, clock, books),
                unknownResolveBackoffMs = 0L,
            ),
            MT5PartialPlacement(
                profile,
                client,
                bus,
                clock,
                books,
                brokerEvents,
                scheduler,
                partials,
                seedTrackedTickets = {},
                unknownResolveBackoffMs = 0L,
            ),
        )
    private val events = CopyOnWriteArrayList<BrokerEvent>()
    private val filled = CompletableFuture<BrokerEvent.OrderFilled>()
    private val request =
        OrderRequest.Market(
            id = "entry-1",
            symbol = "EXNESS:XAUUSD",
            side = Side.BUY,
            quantity = BigDecimal("1.00"),
            timeInForce = TimeInForce.GTC,
            timestamp = sentAt,
            strategyId = "gold",
        )
    private val placement =
        MT5OrderRequest(
            symbol = "XAUUSDm",
            volume = BigDecimal("1.00"),
            type = "BUY",
            magic = 10001,
            comment = "entry-1",
            clientOrderId = "cid-1",
        )

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderRejected> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderFilled> {
            events.add(it)
            filled.complete(it)
        }
    }

    @AfterEach
    fun stop() = gateway.close()

    private fun reply(
        retcode: Int,
        order: Long,
        deal: Long,
        price: String,
        volume: String? = null,
        error: String? = null,
    ) = MT5OrderResponse(
        MT5OrderResult(retcode, order, deal, BigDecimal(price), "", volume?.let(::BigDecimal)),
        error,
    )

    @Test
    fun `a refusal with no message is rejected with its retcode`() {
        results.handlePlacementResult(request, placement, sentAt, null, reply(10019, 0L, 0L, "0"))

        val rejected = events.single() as BrokerEvent.OrderRejected
        assertThat(rejected.reason).isEqualTo("retcode=10019")
    }

    @Test
    fun `an instant fill with no order ticket is tracked under its deal ticket`() {
        results.handlePlacementResult(request, placement, sentAt, null, reply(10009, 0L, 77L, "2400.35"))

        assertThat(events.map { it::class }).containsExactly(
            BrokerEvent.OrderAccepted::class,
            BrokerEvent.OrderFilled::class,
        )
        assertThat((events[0] as BrokerEvent.OrderAccepted).brokerOrderId).isEqualTo("77")
        assertThat(books.positionBook.meta(77L)?.orderId).isEqualTo("entry-1")
    }

    @Test
    fun `a partial fill reply that names no residual is settled from the venue position`() {
        gateway.on("/orders", "[]")
        val held = Mt5RoutedGateway.position(500, "1.00", priceOpen = "2401", comment = "entry-1", clientOrderId = "cid-1")
        gateway.on("/get_positions", "[$held]")

        val partial = reply(10010, 900L, 7001L, "2400", volume = null)

        results.handlePlacementResult(request, placement, sentAt, null, partial)

        val fill = filled.get(5, TimeUnit.SECONDS)
        assertThat(fill.brokerOrderId).isEqualTo("500")
        assertThat(fill.price).isEqualByComparingTo("2401")
        assertThat(fill.quantity).isEqualByComparingTo("1.00")
    }
}
