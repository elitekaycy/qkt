package com.qkt.app

import com.qkt.app.OrderManagerBracketFixtures.ATTACH_VENUE
import com.qkt.app.OrderManagerBracketFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Submit-time protection on a market bracket is judged against the quote the entry executes at —
 * the ask for a BUY, the bid for a SELL — exactly as the MT5 gateway's validate_sl_tp judges it.
 */
class OrderManagerBracketExecQuoteValidationTest {
    @Test
    fun `market BUY bracket is validated against the ask it fills at, not the mid`() {
        // The live scale-burst trace: a stack TP 0.093 below the ask, above the mid, was rejected
        // by the gateway; judged against the mid it would have passed locally.
        val (om, _) = quotedManager(bid = "99.8", mid = "100", ask = "100.2")

        val ack = om.submit(marketBracket(Side.BUY, tp = "100.1", sl = "90"))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("For BUY orders, TP must be above entry price")
    }

    @Test
    fun `market SELL bracket is validated against the bid it fills at, not the mid`() {
        val (om, _) = quotedManager(bid = "99.8", mid = "100", ask = "100.2")

        val ack = om.submit(marketBracket(Side.SELL, tp = "99.9", sl = "110"))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("For SELL orders, TP must be below entry price")
    }

    @Test
    fun `a BUY stop between the mid and the ask is valid, as the venue accepts it`() {
        val (om, broker) = quotedManager(bid = "99.8", mid = "100", ask = "100.2")

        val ack = om.submit(marketBracket(Side.BUY, tp = "110", sl = "100.1"))

        assertThat(ack.accepted).isTrue()
        assertThat(broker.submits).isNotEmpty()
    }

    @Test
    fun `a relative target smaller than half the spread is accepted on the live path`() {
        // A primary `TAKE PROFIT BY 0.1` on a 0.4-spread quote: its placeholder sits below the ask,
        // which the gateway refused when it was sent. It no longer is — the target attaches at fill.
        val (om, broker) = quotedManager(bid = "99.8", mid = "100", ask = "100.2")

        val ack =
            om.submit(
                marketBracket(Side.BUY, tp = "100.1", sl = "90").copy(
                    takeProfitAst = ChildBy(NumLit(java.math.BigDecimal("0.1"))),
                ),
            )

        assertThat(ack.accepted).isTrue()
        assertThat(broker.submits).isNotEmpty()
    }

    private fun quotedManager(
        bid: String,
        mid: String,
        ask: String,
    ): Pair<OrderManager, FakeBroker> {
        val clock = FixedClock(0L)
        val bus = newBus()
        val broker = FakeBroker(bus, clock, ATTACH_VENUE)
        val prices = MarketPriceTracker()
        prices.update(com.qkt.marketdata.Tick("X", Money.of(mid), 1L, bid = Money.of(bid), ask = Money.of(ask)))
        return OrderManager(broker, bus, prices, clock) to broker
    }

    private fun marketBracket(
        side: Side,
        tp: String,
        sl: String,
    ): OrderRequest.Bracket =
        OrderRequest.Bracket(
            id = "b1",
            symbol = "X",
            side = side,
            quantity = Money.of("1"),
            entry =
                OrderRequest.Market(
                    id = "e1",
                    symbol = "X",
                    side = side,
                    quantity = Money.of("1"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            takeProfit = Money.of(tp),
            stopLoss = StopLossSpec.Fixed(Money.of(sl)),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            takeProfitAst = ChildAt(NumLit(Money.of(tp))),
        )
}
