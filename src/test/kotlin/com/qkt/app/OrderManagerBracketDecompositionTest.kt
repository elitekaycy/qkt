package com.qkt.app

import com.qkt.app.OrderManagerBracketFixtures.bracket
import com.qkt.app.OrderManagerBracketFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerBracketDecompositionTest {
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
