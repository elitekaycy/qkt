package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerOcoTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun limit(
        id: String,
        side: Side,
        price: String,
    ) = OrderRequest.Limit(
        id = id,
        symbol = "X",
        side = side,
        quantity = Money.of("1"),
        limitPrice = Money.of(price),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

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
    fun `OCO holds leg2 until leg1 is accepted`() {
        // Event-driven sequencing: leg2 is only sent once the venue confirms leg1's
        // acceptance, so a leg1 rejection can never leave a one-legged (directional) OCO.
        // emitAcceptOnSubmit=false models a real venue that acks asynchronously after submit.
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        broker.emitAcceptOnSubmit = false
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        // Only leg1 is out; leg2 waits for leg1's acceptance.
        assertThat(broker.submits.map { it.id }).containsExactly("l1")

        broker.emitAccept("l1")

        // leg1 accepted -> leg2 now placed.
        assertThat(broker.submits.map { it.id }).containsExactly("l1", "l2")
    }

    @Test
    fun `leg1 fill before leg2 is acknowledged defers the sibling cancel until leg2 is accepted`() {
        // The async edge: leg1 is accepted, leg2 is sent, then leg1 FILLS before leg2's
        // acceptance arrives. leg2's venue ticket isn't known yet, so cancelling it now would
        // no-op at the venue. The cancel must wait for leg2's acceptance and fire then.
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        broker.emitAcceptOnSubmit = false
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        broker.emitAccept("l1")
        // leg2 is now out but unacknowledged.
        assertThat(broker.submits.map { it.id }).containsExactly("l1", "l2")

        // leg1 fills before leg2's acceptance — the cancel is deferred, not fired now.
        broker.emitFill(broker.submits.first { it.id == "l1" }, price = Money.of("100"))
        assertThat(om.getOrder("l1")?.state).isEqualTo(OrderState.FILLED)
        assertThat(broker.cancels).doesNotContain("l2")

        // leg2 is finally acknowledged — the deferred cancel fires.
        broker.emitAccept("l2")
        assertThat(broker.cancels).contains("l2")
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

    @Test
    fun `submits both legs of OCO to broker`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(broker.submits.map { it.id }).containsExactlyInAnyOrder("l1", "l2")
    }

    @Test
    fun `leg1 fill cancels leg2`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        broker.emitFill(broker.submits.first { it.id == "l1" }, price = Money.of("100"))

        assertThat(om.getOrder("l1")?.state).isEqualTo(OrderState.FILLED)
        assertThat(broker.cancels).contains("l2")
    }

    @Test
    fun `leg2 fill cancels leg1`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        broker.emitFill(broker.submits.first { it.id == "l2" }, price = Money.of("120"))

        assertThat(broker.cancels).contains("l1")
    }

    @Test
    fun `cancelling the OCO group cancels both legs`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        om.cancel("oco1")

        assertThat(broker.cancels).containsExactlyInAnyOrder("l1", "l2")
    }

    @Test
    fun `leg2 rejection cancels the live leg1`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        broker.rejectOrderIds.add("l2")
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val ack =
            om.submit(
                OrderRequest.StandaloneOCO(
                    id = "oco1",
                    symbol = "X",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    leg1 = limit("l1", Side.BUY, "100"),
                    leg2 = limit("l2", Side.SELL, "120"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(broker.cancels).contains("l1")
        assertThat(ack.accepted).isFalse
        assertThat(om.getOrder("oco1")?.state).isEqualTo(OrderState.REJECTED)
    }

    @Test
    fun `leg1 rejection abandons the OCO without placing leg2`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        broker.rejectOrderIds.add("l1")
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val ack =
            om.submit(
                OrderRequest.StandaloneOCO(
                    id = "oco1",
                    symbol = "X",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    leg1 = limit("l1", Side.BUY, "100"),
                    leg2 = limit("l2", Side.SELL, "120"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(broker.submits.map { it.id }).doesNotContain("l2")
        assertThat(ack.accepted).isFalse
        assertThat(om.getOrder("oco1")?.state).isEqualTo(OrderState.REJECTED)
    }
}
