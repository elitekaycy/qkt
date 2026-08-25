package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerBracketTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun bracket(): OrderRequest.Bracket {
        val entry =
            OrderRequest.Limit(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        return OrderRequest.Bracket(
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
    }

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
    fun `bracket whose take profit is already reached still submits — only broken stops reject`() {
        // A reachable target is an instant profit-take, not inverted protection; BY-resolved
        // targets are anchored to the signal bar and legitimately trail the submit quote.
        val clock = FixedClock(0L)
        val bus = newBus()
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val reached = bracket().copy(takeProfit = Money.of("99"))

        val ack = om.submit(reached)

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

    @Test
    fun `Bracket with native capability ships whole to broker`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val caps =
            setOf(
                OrderTypeCapability.MARKET,
                OrderTypeCapability.LIMIT,
                OrderTypeCapability.BRACKET,
            )
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket())

        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single()).isInstanceOf(OrderRequest.Bracket::class.java)
    }

    @Test
    fun `Bracket without native capability decomposes to entry first`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val caps =
            setOf(
                OrderTypeCapability.MARKET,
                OrderTypeCapability.LIMIT,
                OrderTypeCapability.STOP,
            )
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket())

        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single().id).isEqualTo("e1")
    }

    @Test
    fun `Bracket fallback - entry fill activates TP and SL legs`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val caps =
            setOf(
                OrderTypeCapability.MARKET,
                OrderTypeCapability.LIMIT,
                OrderTypeCapability.STOP,
            )
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))

        assertThat(broker.submits.size).isGreaterThanOrEqualTo(3)
        assertThat(broker.submits.map { it::class.simpleName })
            .contains("Limit", "Stop")
    }

    @Test
    fun `relative bracket exits re-anchor to actual fill`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP),
            )
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val request =
            bracket().copy(
                stopLossAst = ChildPct(NumLit(Money.of("5"))),
                takeProfitAst = ChildPct(NumLit(Money.of("10"))),
            )

        om.submit(request)
        broker.emitFill(broker.submits.single(), price = Money.of("102"))

        val tp = broker.submits.filterIsInstance<OrderRequest.Limit>().first { it.id == "b1-tp" }
        val sl = broker.submits.filterIsInstance<OrderRequest.Stop>().first { it.id == "b1-sl" }
        assertThat(tp.limitPrice).isEqualByComparingTo("112.2")
        assertThat(sl.stopPrice).isEqualByComparingTo("96.9")
    }

    @Test
    fun `Bracket fallback - TP fill cancels SL`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val caps = setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP)
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        val tp = broker.submits.first { it is OrderRequest.Limit && it.id != "e1" }
        broker.emitFill(tp, price = Money.of("110"))

        val slId = broker.submits.first { it is OrderRequest.Stop }.id
        assertThat(broker.cancels).contains(slId)
    }
}
