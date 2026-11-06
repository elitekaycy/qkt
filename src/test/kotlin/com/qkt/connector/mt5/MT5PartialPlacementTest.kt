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

/** 1.00 lot sent, 0.40 done under order 900 / deal 7001, but history never shows deal 7001. */
class MT5PartialPlacementTest {
    private val sentAt = 1_800_000_000_000L
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(time = sentAt + 1_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val events = mutableListOf<BrokerEvent>()
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
        )
    private val response =
        MT5OrderResponse(
            MT5OrderResult(
                retcode = MT5_TRADE_RETCODE_DONE_PARTIAL,
                order = 900L,
                deal = 7001L,
                price = BigDecimal("2400"),
                comment = "",
                volume = BigDecimal("0.40"),
            ),
        )

    init {
        bus.subscribe<BrokerEvent.GatewayUnreachable> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderAccepted> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { events.add(it) }
    }

    @AfterEach
    fun stop() = gateway.close()

    private fun partials() =
        MT5PartialPlacement(
            profile,
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            bus,
            clock,
            books,
            MT5BrokerEvents(profile, bus, clock),
            MT5UnknownResolveScheduler(profile.name, periodicResolveMs = 3_600_000L),
            MT5PartialEntries(books, bus, clock),
            seedTrackedTickets = {},
            unknownResolveBackoffMs = 0L,
        )

    @Test
    fun `a deal that never shows in history leaves the outcome unknown and alerts the operator`() {
        // Deal 7001 is in history but on another symbol, so it cannot be this entry's opening deal.
        gateway.on(
            "/history_deals_get",
            "[" + deal(7001, 500, entry = 0, volume = "0.40", price = "1.1", symbol = "EURUSDm") + "]",
        )

        partials().resolvePartialPlacement(request, placement, sentAt, null, response)

        val alert = events.single() as BrokerEvent.GatewayUnreachable
        assertThat(alert.consecutiveFailures).isEqualTo(MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS)
        assertThat(gateway.calls("/history_deals_get")).hasSize(MT5BrokerLimits.UNKNOWN_RESOLVE_ATTEMPTS)
        assertThat(books.partialEntryByPositionTicket).isEmpty()
    }
}
