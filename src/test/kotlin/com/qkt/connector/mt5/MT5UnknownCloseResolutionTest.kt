package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.connector.mt5.Mt5RoutedGateway.Companion.deal
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A close of ticket 9001 whose reply was lost, settled from the venue's deals. */
class MT5UnknownCloseResolutionTest {
    private val sentAt = 1_800_000_000_000L
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(time = sentAt + 1_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val events = mutableListOf<BrokerEvent>()
    private val tenth = BigDecimal("0.10")
    private val close =
        OrderRequest.Market(
            id = "dsl-gold--4-exit",
            symbol = "EXNESS:XAUUSD",
            side = Side.SELL,
            quantity = BigDecimal("0.10"),
            timeInForce = TimeInForce.GTC,
            timestamp = sentAt,
            strategyId = "gold",
        )

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderFilled> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderCancelled> { events.add(it) }
    }

    @AfterEach
    fun stop() = gateway.close()

    private fun resolution(alreadyPublished: Boolean) =
        MT5UnknownCloseResolution(
            profile,
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            bus,
            clock,
            books,
            MT5BrokerEvents(profile, bus, clock),
            MT5EngineCloseMarkers(profile, clock),
            MT5UnknownResolveScheduler(profile.name, periodicResolveMs = 60_000L),
            hasPublishedClose = { alreadyPublished },
            unknownResolveBackoffMs = 0L,
        )

    private fun venueClosedTheTicket() {
        books.positionBook.track(9001L, MT5TicketMeta("dsl-gold--4", "gold"), "EXNESS:XAUUSD", 0L)
        gateway.on("/get_positions", "[]")
        gateway.on(
            "/history_deals_get",
            "[" + deal(7, 9001, entry = 1, volume = "0.10", price = "2410", type = 1, timeMs = sentAt + 200) + "]",
        )
    }

    @Test
    fun `a close the position poller already published retires the exit order without a second fill`() {
        venueClosedTheTicket()

        resolution(alreadyPublished = true).resolveUnknownCloseOutcome(close, 9001L, tenth, sentAt, "timeout")

        val cancelled = events.single() as BrokerEvent.OrderCancelled
        assertThat(cancelled.clientOrderId).isEqualTo("dsl-gold--4-exit")
        assertThat(cancelled.brokerOrderId).isEqualTo("9001")
        assertThat(cancelled.reason).contains("already published by the position poller")
        assertThat(books.positionBook.isAttributed(9001L)).isFalse()
    }

    @Test
    fun `a close found in the deals and not yet published is booked as the exit fill`() {
        venueClosedTheTicket()

        resolution(alreadyPublished = false).resolveUnknownCloseOutcome(close, 9001L, tenth, sentAt, "timeout")

        assertThat(events.map { it::class }).containsExactly(
            BrokerEvent.OrderAccepted::class,
            BrokerEvent.OrderFilled::class,
        )
        val fill = events[1] as BrokerEvent.OrderFilled
        assertThat(fill.price).isEqualByComparingTo("2410")
        assertThat(fill.quantity).isEqualByComparingTo("0.10")
    }
}
