package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerFillAnchoredTimeExitTest {
    private fun market(
        id: String,
        side: Side = Side.BUY,
    ) = OrderRequest.Market(
        id = id,
        symbol = "X",
        side = side,
        quantity = Money.of("1"),
        timeInForce = TimeInForce.GTC,
        timestamp = 1_000L,
    )

    private fun timeExit(
        target: OrderRequest,
        holdMs: Long = 90_000L,
    ) = OrderRequest.TimeExit(
        id = "te1",
        symbol = "X",
        side = target.side,
        quantity = target.quantity,
        target = target,
        deadline = Instant.ofEpochMilli(1_000L + holdMs),
        onExpiry = ExpiryAction.CLOSE_AT_MARKET,
        timeInForce = TimeInForce.GTC,
        timestamp = 1_000L,
        holdMs = holdMs,
    )

    private class Rig(
        openLegQuantity: ((String, String) -> BigDecimal?)? = null,
        mode: PositionAccountingMode = PositionAccountingMode.UNKNOWN,
    ) {
        val clock = FixedClock(time = 1_000L)
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP),
            )
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                positionMode = { mode },
                openLegQuantity = openLegQuantity,
            )

        fun tickAt(ts: Long) {
            clock.time = ts
            bus.publish(TickEvent(Tick("X", Money.of("100"), ts)))
        }

        fun closes() = broker.submits.filter { it is OrderRequest.Market && it.id.endsWith("-close") }
    }

    @Test
    fun `fill-anchored exit times from the entry fill, not from submit`() {
        val rig = Rig()
        val entry = market("e1")
        rig.om.submit(timeExit(entry))
        rig.clock.time = 50_000L
        rig.broker.emitFill(entry, price = Money.of("100"))

        rig.tickAt(139_999L)
        assertThat(rig.closes()).isEmpty()

        rig.tickAt(140_000L)
        val close = rig.closes().single()
        assertThat(close.side).isEqualTo(Side.SELL)
        assertThat(close.quantity).isEqualByComparingTo(Money.of("1"))
    }

    @Test
    fun `fill-anchored exit on a bracket stands its exits down and closes the entry`() {
        val rig = Rig()
        val bracket =
            OrderRequest.Bracket(
                id = "b1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                entry = market("b1-entry"),
                takeProfit = Money.of("110"),
                stopLoss = StopLossSpec.Fixed(Money.of("90")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
            )
        rig.om.submit(timeExit(bracket))
        rig.broker.emitFill(market("b1-entry"), price = Money.of("100"))

        rig.tickAt(91_000L)

        assertThat(rig.closes()).hasSize(1)
        assertThat(rig.broker.cancels).contains("b1-tp", "b1-sl")
    }

    @Test
    fun `fill-anchored exit does nothing when the leg already closed`() {
        val rig = Rig(openLegQuantity = { _, _ -> null }, mode = PositionAccountingMode.HEDGING)
        val entry = market("e1")
        rig.om.submit(timeExit(entry))
        rig.broker.emitFill(entry, price = Money.of("100"))

        rig.tickAt(200_000L)

        assertThat(rig.closes()).isEmpty()
    }

    @Test
    fun `fill-anchored exit closes only what is left of the leg`() {
        val rig = Rig(openLegQuantity = { _, _ -> Money.of("0.4") }, mode = PositionAccountingMode.HEDGING)
        val entry = market("e1")
        rig.om.submit(timeExit(entry))
        rig.broker.emitFill(entry, price = Money.of("100"))

        rig.tickAt(200_000L)

        assertThat(rig.closes().single().quantity).isEqualByComparingTo(Money.of("0.4"))
    }

    @Test
    fun `an exit whose entry is rejected is dropped without a close`() {
        val rig = Rig()
        rig.broker.rejectOrderIds += "e1"
        rig.om.submit(timeExit(market("e1")))

        rig.tickAt(2_000L)
        rig.tickAt(500_000L)

        assertThat(rig.closes()).isEmpty()
    }
}
