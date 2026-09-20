package com.qkt.app

import com.qkt.app.OrderManagerStackFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.events.BrokerEvent
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.OrderRequest
import com.qkt.execution.StackPlan
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerStackLayerBracketTest {
    @Test
    fun `layer 1 fill attaches both SL and TP as OCO siblings`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val plan =
            StackPlan(
                layers =
                    listOf(
                        LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), com.qkt.dsl.ast.Market, Immediate),
                    ),
                outerBracket =
                    com.qkt.dsl.ast.BracketAst(
                        stopLoss = ChildBy(NumLit(BigDecimal("50"))),
                        takeProfit = ChildBy(NumLit(BigDecimal("200"))),
                    ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-tp",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${req.id}-l1",
                brokerOrderId = "b1",
                symbol = "BTCUSDT",
                side = Side.BUY,
                price = BigDecimal("50000"),
                quantity = BigDecimal("0.1"),
                timestamp = clock.now(),
            ),
        )
        val active = manager.activeOrders()
        assertThat(active.any { it.id == "${req.id}-l1-sl" }).isTrue
        assertThat(active.any { it.id == "${req.id}-l1-tp" }).isTrue
        val sl = active.first { it.id == "${req.id}-l1-sl" }.request as OrderRequest.Stop
        val tp = active.first { it.id == "${req.id}-l1-tp" }.request as OrderRequest.Limit
        assertThat(sl.stopPrice).isEqualByComparingTo(BigDecimal("49950"))
        assertThat(tp.limitPrice).isEqualByComparingTo(BigDecimal("50200"))
    }

    @Test
    fun `layer fill attaches SL and TP to the venue position when POSITION_MODIFY is supported`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.POSITION_MODIFY),
            )
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val plan =
            StackPlan(
                layers =
                    listOf(
                        LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), com.qkt.dsl.ast.Market, Immediate),
                    ),
                outerBracket =
                    BracketAst(
                        stopLoss = ChildBy(NumLit(BigDecimal("50"))),
                        takeProfit = ChildBy(NumLit(BigDecimal("200"))),
                    ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-attach",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${req.id}-l1",
                brokerOrderId = "TKT-42",
                symbol = "BTCUSDT",
                side = Side.BUY,
                price = BigDecimal("50000"),
                quantity = BigDecimal("0.1"),
                timestamp = clock.now(),
            ),
        )

        // The exits attach to the venue position by ticket — no resting -sl/-tp orders that would
        // open a counter on a hedging account.
        assertThat(broker.modifyPositions).hasSize(1)
        val m = broker.modifyPositions.single()
        assertThat(m.ticket).isEqualTo("TKT-42")
        assertThat(m.sl).isEqualByComparingTo(BigDecimal("49950"))
        assertThat(m.tp).isEqualByComparingTo(BigDecimal("50200"))
        assertThat(manager.activeOrders().none { it.id == "${req.id}-l1-sl" }).isTrue
        assertThat(manager.activeOrders().none { it.id == "${req.id}-l1-tp" }).isTrue
    }

    @Test
    fun `TP fill cancels SL as OCO sibling`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val plan =
            StackPlan(
                layers =
                    listOf(
                        LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), com.qkt.dsl.ast.Market, Immediate),
                    ),
                outerBracket =
                    com.qkt.dsl.ast.BracketAst(
                        stopLoss = ChildBy(NumLit(BigDecimal("50"))),
                        takeProfit = ChildBy(NumLit(BigDecimal("200"))),
                    ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-tp2",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${req.id}-l1",
                brokerOrderId = "b1",
                symbol = "BTCUSDT",
                side = Side.BUY,
                price = BigDecimal("50000"),
                quantity = BigDecimal("0.1"),
                timestamp = clock.now(),
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${req.id}-l1-tp",
                brokerOrderId = "b2",
                symbol = "BTCUSDT",
                side = Side.SELL,
                price = BigDecimal("50200"),
                quantity = BigDecimal("0.1"),
                timestamp = clock.now(),
            ),
        )
        val sl = manager.activeOrders().firstOrNull { it.id == "${req.id}-l1-sl" }
        assertThat(sl).isNull()
    }
}
