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
import com.qkt.persistence.FileStatePersistor
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.StatePersistor
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * #1360: an `EXIT AFTER` close the venue ends unfilled used to drop its timer, leaving the leg open.
 * The exit now stays armed until its close fills: a close cancelled or rejected unfilled is resent a
 * minute later for what is still open, and repeated failures alert. The entry fills at 1s with a
 * 90s hold, so the first close goes at 91s.
 */
class OrderManagerTimedCloseRetryTest {
    private val clock = FixedClock(time = 1_000L)
    private var open: BigDecimal? = Money.of("1")

    private inner class Session(
        persistor: StatePersistor = NoopStatePersistor(),
    ) {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val alerts = mutableListOf<String>()
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                persistor,
                onProtectionFailure = { _, message -> alerts += message },
                positionMode = { PositionAccountingMode.HEDGING },
                openLegQuantity = { _, _ -> open },
            )

        fun tickAt(ts: Long) {
            clock.time = ts
            bus.publish(TickEvent(Tick("X", Money.of("100"), ts)))
        }

        fun closes() = broker.submits.filterIsInstance<OrderRequest.Market>().filter { it.id.contains("-close") }

        fun cancel(order: OrderRequest) =
            bus.publish(BrokerEvent.OrderCancelled(order.id, order.id, "cancelled at the venue", "alpha", clock.now()))

        fun reject(order: OrderRequest) = bus.publish(BrokerEvent.OrderRejected(order.id, order.id, "band", "alpha"))
    }

    private fun armed(session: Session): Session {
        val entry =
            OrderRequest.Market("e1", "X", Side.BUY, Money.of("1"), TimeInForce.GTC, 1_000L, strategyId = "alpha")
        session.om.submit(
            OrderRequest.TimeExit(
                id = "te1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                target = entry,
                deadline = Instant.ofEpochMilli(91_000L),
                onExpiry = ExpiryAction.CLOSE_AT_MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
                strategyId = "alpha",
                holdMs = 90_000L,
            ),
        )
        session.broker.emitFill(entry, price = Money.of("100"))
        session.tickAt(91_000L)
        assertThat(session.closes()).hasSize(1)
        return session
    }

    @Test
    fun `a timed close the venue cancels unfilled is sent again`() {
        val s = armed(Session())
        s.cancel(s.closes().single())

        s.tickAt(120_000L)
        assertThat(s.closes()).hasSize(1)
        s.tickAt(151_000L)

        assertThat(s.closes()).hasSize(2)
        val retry = s.closes().last()
        assertThat(retry.id).isEqualTo("te1-close-r1")
        assertThat(retry.side).isEqualTo(Side.SELL)
        assertThat(retry.quantity).isEqualByComparingTo("1")
    }

    @Test
    fun `a timed close filled in part then cancelled resends only what is open`() {
        val s = armed(Session())
        open = Money.of("0.4")
        s.cancel(s.closes().single())

        s.tickAt(151_000L)

        assertThat(s.closes().last().quantity).isEqualByComparingTo("0.4")
    }

    @Test
    fun `a filled timed close ends the exit and a working one is waited for`() {
        val s = armed(Session())
        s.tickAt(151_000L)
        assertThat(s.closes()).hasSize(1)

        s.broker.emitFill(s.closes().single(), price = Money.of("100"))
        s.tickAt(300_000L)

        assertThat(s.closes()).hasSize(1)
    }

    @Test
    fun `three unfilled timed closes in a row raise the operator alert and retrying continues`() {
        val s = armed(Session())
        var at = 91_000L
        repeat(3) {
            if (it % 2 == 0) s.cancel(s.closes().last()) else s.reject(s.closes().last())
            at += 60_000L
            s.tickAt(at)
        }

        assertThat(s.closes()).hasSize(4)
        assertThat(s.alerts).singleElement().asString().contains("te1", "X", "3 consecutive", "position 1")
    }

    @Test
    fun `a timed close cancelled before a restart is resent after it`(
        @TempDir state: Path,
    ) {
        val first = armed(Session(FileStatePersistor(state)))
        first.cancel(first.closes().single())

        val second = Session(FileStatePersistor(state))
        second.om.restore(listOf("alpha"))
        second.tickAt(151_000L)

        assertThat(second.closes().map { it.id }).containsExactly("te1-close-r1")
    }
}
