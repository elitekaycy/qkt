package com.qkt.app

import com.qkt.app.OrderManagerOcoFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerOcoBracketLegTest {
    private fun trailingBracket(
        bracketId: String,
        entryId: String,
        side: Side,
        stopPrice: String,
    ) = OrderRequest.Bracket(
        id = bracketId,
        symbol = "X",
        side = side,
        quantity = Money.of("1"),
        entry =
            OrderRequest.Stop(
                id = entryId,
                symbol = "X",
                side = side,
                quantity = Money.of("1"),
                stopPrice = Money.of(stopPrice),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        takeProfit = Money.of("130"),
        stopLoss = StopLossSpec.ArmedTrail(trailDistance = Money.of("18"), mfeThreshold = Money.of("18")),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `bracket-legged OCO entry cancels the sibling when one leg fills`() {
        // hedge-straddle shape: OCO_ENTRY of two trailing-stop brackets. A bracket (id b1/b2)
        // wraps an entry Stop with a DIFFERENT id (e1/e2) — the broker fills the entry, so the
        // fill arrives under e1/e2, not b1/b2. The OCO sibling link must be keyed by the entry
        // id, or the sibling never cancels and a whipsaw fills both legs (the prod "back to
        // back" net-zero hedge accumulation, #269).
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP),
            )
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = trailingBracket("b1", "e1", Side.BUY, "110"),
                leg2 = trailingBracket("b2", "e2", Side.SELL, "90"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(broker.submits.map { it.id }).contains("e1", "e2")

        broker.emitFill(broker.submits.first { it.id == "e1" }, price = Money.of("110"))

        assertThat(om.getOrder("e1")?.state).isEqualTo(OrderState.FILLED)
        assertThat(broker.cancels).contains("e2")
    }

    @Test
    fun `bracket-legged straddle stays hedge-safe under delayed venue accepts`() {
        // End-to-end of the prod hedge-straddle (#269) against a venue that acknowledges
        // acceptance asynchronously: leg2's entry is held until leg1's entry is accepted, and
        // when leg1 fills before leg2's entry is acknowledged the losing-side cancel is deferred
        // until leg2's ticket is known. At no point are both entries live as a directional bet.
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP),
            )
        broker.emitAcceptOnSubmit = false
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = trailingBracket("b1", "e1", Side.BUY, "110"),
                leg2 = trailingBracket("b2", "e2", Side.SELL, "90"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        // Only leg1's entry is placed; leg2's entry waits for leg1's acceptance.
        assertThat(broker.submits.map { it.id }).containsExactly("e1")

        broker.emitAccept("e1")
        assertThat(broker.submits.map { it.id }).contains("e1", "e2")

        // leg1's entry fills before leg2's entry is acknowledged — the sibling cancel is deferred,
        // not fired against an unknown venue ticket.
        broker.emitFill(broker.submits.first { it.id == "e1" }, price = Money.of("110"))
        assertThat(om.getOrder("e1")?.state).isEqualTo(OrderState.FILLED)
        assertThat(broker.cancels).doesNotContain("e2")

        // leg2's entry is finally acknowledged — the deferred cancel fires, closing the window.
        broker.emitAccept("e2")
        assertThat(broker.cancels).contains("e2")
    }
}
