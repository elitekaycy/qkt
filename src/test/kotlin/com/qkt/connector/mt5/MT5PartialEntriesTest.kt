package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5PartialEntriesTest {
    private val clock = FixedClock(50_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(MT5DefaultProfiles.exness.copy(gatewayUrl = "http://127.0.0.1:1"))
    private val partials = MT5PartialEntries(books, bus, clock)
    private val published = mutableListOf<BrokerEvent>()
    private val meta = MT5TicketMeta("entry-1", "gold_trend")

    init {
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> published.add(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> published.add(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> published.add(e) }
    }

    /** 1.00 lot requested, 0.40 already filled at 2400.00; residual order 900 rests, position is 500. */
    private fun registerPartial() =
        partials.registerPartialEntry(
            PartialEntryState(
                meta = meta,
                residualTicket = 900L,
                positionTicket = 500L,
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                requestedQuantity = BigDecimal("1.00"),
                cumulativeFilled = BigDecimal("0.40"),
                averageFillPrice = BigDecimal("2400.00"),
            ),
            openedAtMs = 49_000L,
        )

    private fun position(
        volume: String,
        averagePrice: String,
        ticket: Long = 500L,
    ) = MT5Position(
        ticket = ticket,
        symbol = "XAUUSDm",
        type = 0,
        volume = BigDecimal(volume),
        priceOpen = BigDecimal(averagePrice),
        sl = BigDecimal.ZERO,
        tp = BigDecimal.ZERO,
        profit = BigDecimal.ZERO,
        magic = 1,
        openTime = 49_000L,
    )

    @Test
    fun `registering makes the residual cancellable and the position attributed`() {
        registerPartial()

        assertThat(books.pendingBook.ticketOf("entry-1")).isEqualTo(900L)
        assertThat(books.positionBook.meta(500L)).isEqualTo(meta)
    }

    @Test
    fun `registering hands back a position the poller saw before the placement reply`() {
        val early = position("0.40", "2400.00")
        books.earlyPositionByTicket[500L] = early

        assertThat(registerPartial()).isSameAs(early)
        assertThat(books.earlyPositionByTicket).isEmpty()
    }

    @Test
    fun `a growing position publishes only the new slice at the price that slice traded`() {
        registerPartial()

        // 0.40 @ 2400 grows to 0.80 @ 2401 average: the new 0.40 traded at 2402.
        assertThat(partials.reconcilePartialEntry(position("0.80", "2401.00"))).isTrue()

        val slice = published.single() as BrokerEvent.OrderPartiallyFilled
        assertThat(slice.quantity).isEqualByComparingTo("0.40")
        assertThat(slice.price).isEqualByComparingTo("2402.00")
        assertThat(slice.cumulativeFilled).isEqualByComparingTo("0.80")
        assertThat(slice.clientOrderId).isEqualTo("entry-1")
    }

    @Test
    fun `the slice that completes the order is a fill and retires the residual`() {
        registerPartial()

        partials.reconcilePartialEntry(position("1.00", "2400.00"))

        val fill = published.single() as BrokerEvent.OrderFilled
        assertThat(fill.quantity).isEqualByComparingTo("0.60")
        assertThat(fill.brokerOrderId).isEqualTo("500")
        assertThat(books.pendingBook.ticketOf("entry-1")).isNull()
        assertThat(books.recentlyFilledTickets).containsKey(900L)
        assertThat(books.partialEntryByPositionTicket).isEmpty()
        assertThat(books.partialPositionByResidualTicket).isEmpty()
    }

    @Test
    fun `a repeated snapshot is recognised but publishes nothing`() {
        registerPartial()

        assertThat(partials.reconcilePartialEntry(position("0.40", "2400.00"))).isTrue()
        assertThat(published).isEmpty()
    }

    @Test
    fun `a position that is not a partial entry is left to the caller`() {
        registerPartial()

        assertThat(partials.reconcilePartialEntry(position("0.40", "2400.00", ticket = 777L))).isFalse()
    }

    @Test
    fun `a vanished residual cancels the order under the residual ticket and keeps the position attributed`() {
        registerPartial()

        partials.cancelPartialEntryResidual(900L, "residual disappeared from venue after partial fill")

        val cancel = published.single() as BrokerEvent.OrderCancelled
        assertThat(cancel.clientOrderId).isEqualTo("entry-1")
        assertThat(cancel.brokerOrderId).isEqualTo("900")
        assertThat(books.pendingBook.ticketOf("entry-1")).isNull()
        assertThat(books.positionBook.meta(500L)).isEqualTo(meta)
    }
}
