package com.qkt.app

import com.qkt.app.OrderManagerBracketFixtures.ATTACH_VENUE
import com.qkt.app.OrderManagerBracketFixtures.bracket
import com.qkt.app.OrderManagerBracketFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerBracketCrossedProtectionTest {
    @Test
    fun `bracket whose fixed stop is already crossed is rejected locally like venue 10016`() {
        // Pending BUY entry at 100 with SL 101 above it: the stop is breached at birth.
        val clock = FixedClock(0L)
        val bus = newBus()
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val crossed =
            bracket().let { b ->
                b.copy(stopLoss = StopLossSpec.Fixed(Money.of("101")))
            }

        val ack = om.submit(crossed)

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("invalid stops").contains("10016")
        assertThat(broker.submits).isEmpty()
    }

    @Test
    fun `bracket whose absolute take profit is already crossed is rejected like venue 10016`() {
        // The gold RSI-fade tape: a BUY filled at 1320.700 carrying an AT target of 1320.019
        // closed instantly for a 0.68/oz LOSS. An inverted absolute target is broken
        // protection, not a free profit-take, and MT5 rejects it under the same retcode.
        val clock = FixedClock(0L)
        val bus = newBus()
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val crossed =
            bracket().copy(
                takeProfit = Money.of("99"),
                takeProfitAst =
                    com.qkt.dsl.ast
                        .ChildAt(NumLit(java.math.BigDecimal("99"))),
            )

        val ack = om.submit(crossed)

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("take profit").contains("10016")
        assertThat(broker.submits).isEmpty()
    }

    @Test
    fun `an attach venue refuses a relative take profit whose placeholder is inverted`() {
        // An attach venue ships the BY target's pre-fill placeholder with the entry, and the MT5
        // gateway validates it: a BUY placeholder at or below the entry is refused
        // ("For BUY orders, TP must be above entry price"), so the engine refuses it first.
        val clock = FixedClock(0L)
        val bus = newBus()
        val broker = FakeBroker(bus, clock, ATTACH_VENUE)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val relative =
            bracket().copy(
                takeProfit = Money.of("99"),
                takeProfitAst = ChildBy(NumLit(java.math.BigDecimal("5"))),
            )

        val ack = om.submit(relative)

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("For BUY orders, TP must be above entry price")
    }

    @Test
    fun `a venue that splits the bracket never judges a relative target's placeholder`() {
        // Split venues never receive the placeholder; the target re-anchors off the fill.
        val clock = FixedClock(0L)
        val bus = newBus()
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val relative =
            bracket().copy(
                takeProfit = Money.of("99"),
                takeProfitAst = ChildBy(NumLit(java.math.BigDecimal("5"))),
            )

        val ack = om.submit(relative)

        assertThat(ack.accepted).isTrue()
    }

    @Test
    fun `a relative take profit whose placeholder sits beyond the entry is accepted`() {
        val clock = FixedClock(0L)
        val bus = newBus()
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val relative =
            bracket().copy(
                takeProfit = Money.of("105"),
                takeProfitAst = ChildBy(NumLit(java.math.BigDecimal("5"))),
            )

        val ack = om.submit(relative)

        assertThat(ack.accepted).isTrue()
    }

    @Test
    fun `market bracket validates against the last quote and rejects a gapped-through stop`() {
        // The silver gap tick (#1076): quote already below the absolute SL when the BUY submits.
        val clock = FixedClock(0L)
        val bus = newBus()
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val prices = MarketPriceTracker()
        prices.update(com.qkt.marketdata.Tick("X", Money.of("94"), 1L))
        val om = OrderManager(broker, bus, prices, clock)
        val entry =
            OrderRequest.Market(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val crossed =
            OrderRequest.Bracket(
                id = "b1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                entry = entry,
                takeProfit = Money.of("110"),
                stopLoss = StopLossSpec.Fixed(Money.of("95")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )

        val ack = om.submit(crossed)

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("stop loss 95")
    }
}
