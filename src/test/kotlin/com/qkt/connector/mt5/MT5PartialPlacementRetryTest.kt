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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A part-filled entry whose opening deal history does not show yet: interrupted lookups stop, unresolved ones retry. */
class MT5PartialPlacementRetryTest {
    private val sentAt = 1_800_000_000_000L
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(time = sentAt + 1_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val alerts = mutableListOf<BrokerEvent.GatewayUnreachable>()
    private val scheduler = MT5UnknownResolveScheduler(profile.name, periodicResolveMs = 1L)
    private val request =
        OrderRequest.Market("entry-1", "EXNESS:XAUUSD", Side.BUY, BigDecimal("1.00"), TimeInForce.GTC, sentAt, "gold")
    private val placement = MT5OrderRequest("XAUUSDm", BigDecimal("1.00"), "BUY", 10001, comment = "entry-1")
    private val response =
        MT5OrderResponse(
            MT5OrderResult(MT5_TRADE_RETCODE_DONE_PARTIAL, 7001L, 7001L, BigDecimal("2400"), "", BigDecimal("0.40")),
        )

    init {
        bus.subscribe<BrokerEvent.GatewayUnreachable> { alerts.add(it) }
    }

    @AfterEach
    fun stop() {
        scheduler.shutdownNow()
        gateway.close()
    }

    private fun partials(backoffMs: Long) =
        MT5PartialPlacement(
            profile,
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            bus,
            clock,
            books,
            MT5BrokerEvents(profile, bus, clock),
            scheduler,
            MT5PartialEntries(books, bus, clock),
            seedTrackedTickets = {},
            unknownResolveBackoffMs = backoffMs,
        )

    @Test
    fun `an interrupted wait between lookups stops resolving without alerting`() {
        Thread.currentThread().interrupt()
        try {
            partials(backoffMs = 1L).resolvePartialPlacement(request, placement, sentAt, null, response)

            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
        assertThat(alerts).isEmpty()
        assertThat(gateway.calls("/history_deals_get").size).isLessThan(MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS)
    }

    @Test
    fun `an entry that stays unresolved is looked up again later and booked once history shows its deal`() {
        val shown = "[" + deal(7001, 500, entry = 0, volume = "0.40", price = "2400") + "]"
        val attempts = MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS
        gateway.on("/history_deals_get", *Array(attempts) { "[]" }, shown)
        val booked = CountDownLatch(1)
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { booked.countDown() }

        partials(backoffMs = 0L).resolvePartialPlacement(request, placement, sentAt, null, response)

        assertThat(alerts.single().consecutiveFailures).isEqualTo(attempts)
        assertThat(booked.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(books.partialEntryByPositionTicket).containsKey(500L)
    }
}
