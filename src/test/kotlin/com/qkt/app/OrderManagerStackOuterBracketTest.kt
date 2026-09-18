package com.qkt.app

import com.qkt.app.OrderManagerStackFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Side
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

class OrderManagerStackOuterBracketTest {
    @Test
    fun `ChildRr in stack outerBracket computes TP at SL distance times multiplier`() {
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
                        takeProfit =
                            com.qkt.dsl.ast
                                .ChildRr(NumLit(BigDecimal("2.0"))),
                    ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-rr",
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
        // SL distance = 50, RR multiplier = 2.0, so TP distance = 100. TP price = 50000 + 100 = 50100.
        val active = manager.activeOrders()
        val sl = active.first { it.id == "${req.id}-l1-sl" }.request as OrderRequest.Stop
        val tp = active.first { it.id == "${req.id}-l1-tp" }.request as OrderRequest.Limit
        assertThat(sl.stopPrice).isEqualByComparingTo(BigDecimal("49950"))
        assertThat(tp.limitPrice).isEqualByComparingTo(BigDecimal("50100"))
    }
}
