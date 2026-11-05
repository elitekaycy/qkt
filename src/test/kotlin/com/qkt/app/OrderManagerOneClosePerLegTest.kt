package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #1638: at a restart a portfolio book's restored halt flatten and an overdue `EXIT AFTER` both closed
 * the same 1-share leg on a netting venue (Alpaca), leaving an unbooked short. A close already working
 * for a leg now holds back any other close of it, whichever source sends first; the held close is sent
 * only if the working one ends unfilled.
 */
class OrderManagerOneClosePerLegTest {
    private val clock = FixedClock(time = 1_000L)
    private var net: BigDecimal = Money.ZERO

    private inner class Session(
        mode: PositionAccountingMode = PositionAccountingMode.NETTING,
    ) {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                NoopStatePersistor(),
                positionMode = { mode },
                strategyNetQty = { _, _ -> net },
            )

        fun tickAt(ts: Long) {
            clock.time = ts
            bus.publish(TickEvent(Tick("MSFT", Money.of("540"), ts)))
        }

        fun sells() = broker.submits.filterIsInstance<OrderRequest.Market>().filter { it.side == Side.SELL }

        fun fill(order: OrderRequest) {
            if (order.side == Side.SELL) net = net.subtract(order.quantity) else net = net.add(order.quantity)
            broker.emitFill(order, price = Money.of("540"))
        }
    }

    // The flatten's close names the leg and its venue ticket, as SessionFlatten sends it.
    private fun flattenClose(
        id: String = "ORD-book:msft-1",
        legId: String = "book:msft-primary-1",
    ) = OrderRequest.Market(
        id,
        "MSFT",
        Side.SELL,
        Money.of("1"),
        TimeInForce.GTC,
        clock.now(),
        strategyId = "book:msft",
        closesTicket = "ticket-$legId",
        closesLegId = legId,
    )

    // A 1-share long with a 90s `EXIT AFTER`; on a netting venue the exit names no leg.
    private fun held(session: Session): Session {
        val entry =
            OrderRequest.Market(
                "e1",
                "MSFT",
                Side.BUY,
                Money.of("1"),
                TimeInForce.GTC,
                1_000L,
                strategyId = "book:msft",
            )
        session.om.submit(
            OrderRequest.TimeExit(
                id = "te1",
                symbol = "MSFT",
                side = Side.BUY,
                quantity = Money.of("1"),
                target = entry,
                deadline = Instant.ofEpochMilli(91_000L),
                onExpiry = ExpiryAction.CLOSE_AT_MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
                strategyId = "book:msft",
                holdMs = 90_000L,
            ),
        )
        session.fill(entry)
        return session
    }

    @Test
    fun `a flatten close is held back while the timed exit's close of the leg is working`() {
        val s = held(Session())
        s.tickAt(91_000L)
        assertThat(s.sells().map { it.id }).containsExactly("te1-close")

        s.om.submit(flattenClose())
        assertThat(s.sells().map { it.id }).containsExactly("te1-close")

        s.fill(s.sells().single())
        s.tickAt(151_000L)
        assertThat(s.sells().map { it.id }).containsExactly("te1-close")
        assertThat(net).isEqualByComparingTo("0")
    }

    @Test
    fun `a due timed exit waits while a flatten close of the leg is working`() {
        val s = held(Session())
        s.om.submit(flattenClose())
        s.tickAt(91_000L)
        assertThat(s.sells().map { it.id }).containsExactly("ORD-book:msft-1")

        s.fill(s.sells().single())
        s.tickAt(151_000L)
        s.tickAt(211_000L)
        assertThat(s.sells().map { it.id }).containsExactly("ORD-book:msft-1")
        assertThat(net).isEqualByComparingTo("0")
    }

    @Test
    fun `a held close is sent when the working close ends unfilled`() {
        val s = held(Session())
        s.tickAt(91_000L)
        s.om.submit(flattenClose())

        val timed = s.sells().single()
        s.bus.publish(BrokerEvent.OrderCancelled(timed.id, timed.id, "cancelled at the venue", "book:msft", 92_000L))

        assertThat(s.sells().map { it.id }).containsExactly("te1-close", "ORD-book:msft-1")
        s.tickAt(151_000L)
        assertThat(s.sells()).hasSize(2)
    }

    @Test
    fun `a second close naming the same leg is held back on a hedging venue too`() {
        val s = Session(PositionAccountingMode.HEDGING)
        net = Money.of("1")
        s.om.submit(flattenClose(id = "c1", legId = "L1"))
        s.om.submit(flattenClose(id = "c2", legId = "L1"))
        s.om.submit(flattenClose(id = "c3", legId = "L2"))

        assertThat(s.sells().map { it.id }).containsExactly("c1", "c3")
    }
}
