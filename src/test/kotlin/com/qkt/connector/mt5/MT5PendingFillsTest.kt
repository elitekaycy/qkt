package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5PendingFillsTest {
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = "http://127.0.0.1:1", pollIntervalMs = 1_000L)
    private val clock = FixedClock(50_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()
    private val pendingFills =
        MT5PendingFills(
            profile,
            bus,
            clock,
            MT5Symbol(profile.symbolPolicy),
            books,
            MT5PartialEntries(books, bus, clock),
        )
    private val meta = MT5TicketMeta("dsl-gold--1", "gold_trend")

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
    }

    private fun sellPosition(ticket: Long) =
        MT5Position(
            ticket = ticket,
            symbol = "XAUUSDm",
            type = 1,
            volume = BigDecimal("0.10"),
            priceOpen = BigDecimal("2400.50"),
            sl = BigDecimal.ZERO,
            tp = BigDecimal.ZERO,
            profit = BigDecimal.ZERO,
            magic = 1,
            openTime = 49_500L,
        )

    @Test
    fun `a registered ticket turning into a position fills its owner in engine terms`() {
        pendingFills.registerPendingTicket(42L, meta)

        assertThat(pendingFills.onPendingPositionOpened(sellPosition(42L))).isTrue()

        val fill = fills.single()
        assertThat(fill.clientOrderId).isEqualTo("dsl-gold--1")
        assertThat(fill.strategyId).isEqualTo("gold_trend")
        assertThat(fill.symbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(fill.side).isEqualTo(Side.SELL)
        assertThat(fill.price).isEqualByComparingTo("2400.50")
        assertThat(books.positionBook.meta(42L)).isEqualTo(meta)
        assertThat(books.pendingBook.ticketOf("dsl-gold--1")).isNull()
        assertThat(books.recentlyFilledTickets[42L]).isEqualTo(50_000L)
    }

    @Test
    fun `a position seen before the placement reply is parked then filled once the ticket registers`() {
        assertThat(pendingFills.onPendingPositionOpened(sellPosition(42L))).isFalse()
        assertThat(fills).isEmpty()

        pendingFills.registerPendingTicket(42L, meta)

        assertThat(fills.single().clientOrderId).isEqualTo("dsl-gold--1")
        assertThat(books.earlyPositionByTicket).isEmpty()
    }

    @Test
    fun `seeing the same filled ticket again publishes no second fill`() {
        pendingFills.registerPendingTicket(42L, meta)
        pendingFills.onPendingPositionOpened(sellPosition(42L))

        assertThat(pendingFills.onPendingPositionOpened(sellPosition(42L))).isTrue()
        assertThat(fills).hasSize(1)
        assertThat(books.earlyPositionByTicket).isEmpty()
    }

    @Test
    fun `fill markers older than three poll intervals are swept on the next fill`() {
        books.recentlyFilledTickets[7L] = 47_000L
        books.recentlyFilledTickets[8L] = 47_001L
        pendingFills.registerPendingTicket(42L, meta)

        pendingFills.onPendingPositionOpened(sellPosition(42L))

        assertThat(books.recentlyFilledTickets.keys).containsExactlyInAnyOrder(8L, 42L)
    }
}
