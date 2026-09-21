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
import org.junit.jupiter.api.Test

class MT5PartialEntryRecoveryTest {
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = "http://127.0.0.1:1")
    private val clock = FixedClock(90_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val published = mutableListOf<BrokerEvent>()
    private val seeded = mutableListOf<Set<Long>>()
    private val recovery =
        MT5PartialEntryRecovery(
            profile,
            bus,
            clock,
            books,
            MT5PartialEntries(books, bus, clock),
            MT5RequestedProtection(MT5OrderTranslator(profile, MT5Symbol(profile.symbolPolicy))),
            seedTrackedTickets = { seeded.add(it) },
        )

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> published.add(e) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> published.add(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> published.add(e) }
    }

    /** `entry-1` asked for 1.00 lot. */
    private val order =
        ManagedOrder(
            id = "entry-1",
            request =
                OrderRequest.Market(
                    id = "entry-1",
                    symbol = "EXNESS:XAUUSD",
                    side = Side.BUY,
                    quantity = BigDecimal("1.00"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "gold_trend",
                ),
            state = OrderState.WORKING,
            createdAt = 1L,
            lastUpdatedAt = 1L,
        )

    private fun position(volume: String) =
        MT5Position(
            ticket = 500L,
            symbol = "XAUUSDm",
            type = 0,
            volume = BigDecimal(volume),
            priceOpen = BigDecimal("2400.00"),
            sl = BigDecimal.ZERO,
            tp = BigDecimal.ZERO,
            profit = BigDecimal.ZERO,
            magic = 1,
            openTime = 80_000L,
            clientOrderId = "entry-1",
        )

    private val residual =
        MT5PendingOrder(
            ticket = 900L,
            symbol = "XAUUSDm",
            type = "BUY_LIMIT",
            volume = BigDecimal("0.60"),
            priceOpen = BigDecimal("2400.00"),
            sl = BigDecimal.ZERO,
            tp = BigDecimal.ZERO,
            magic = 1,
            timeSetup = 80_000L,
            timeExpiration = 0L,
            clientOrderId = "entry-1",
        )

    @Test
    fun `a part-filled entry with its residual still resting resumes as a working partial`() {
        val recovered =
            recovery.recoverPartialEntries(
                listOf(order),
                listOf(residual),
                listOf(position("0.40")),
                emptySet(),
            )

        assertThat(recovered).containsExactly("entry-1")
        val accepted = published[0] as BrokerEvent.OrderAccepted
        val partial = published[1] as BrokerEvent.OrderPartiallyFilled
        assertThat(published).hasSize(2)
        assertThat(accepted.brokerOrderId).isEqualTo("900")
        assertThat(partial.brokerOrderId).isEqualTo("500")
        assertThat(partial.cumulativeFilled).isEqualByComparingTo("0.40")
        assertThat(seeded).containsExactly(setOf(900L))
        assertThat(books.pendingBook.ticketOf("entry-1")).isEqualTo(900L)
        assertThat(books.partialPositionByResidualTicket[900L]).isEqualTo(500L)
    }

    @Test
    fun `a part-filled entry whose residual is gone is filled in part then cancelled`() {
        recovery.recoverPartialEntries(listOf(order), emptyList(), listOf(position("0.40")), emptySet())

        assertThat(published.map { it::class.simpleName })
            .containsExactly("OrderAccepted", "OrderPartiallyFilled", "OrderCancelled")
        assertThat((published[0] as BrokerEvent.OrderAccepted).brokerOrderId).isEqualTo("500")
        assertThat((published[2] as BrokerEvent.OrderCancelled).reason)
            .isEqualTo("residual absent during partial-entry recovery")
        assertThat(seeded).isEmpty()
    }

    @Test
    fun `a ticket the ledger booked before the restart is tracked but never republished`() {
        val recovered =
            recovery.recoverPartialEntries(
                listOf(order),
                listOf(residual),
                listOf(position("0.40")),
                setOf("500"),
            )

        assertThat(recovered).containsExactly("entry-1")
        assertThat(published).isEmpty()
        assertThat(books.positionBook.meta(500L)?.orderId).isEqualTo("entry-1")
    }

    @Test
    fun `a position already at the requested size is not a partial entry`() {
        val recovered = recovery.recoverPartialEntries(listOf(order), emptyList(), listOf(position("1.00")), emptySet())

        assertThat(recovered).isEmpty()
        assertThat(published).isEmpty()
    }
}
