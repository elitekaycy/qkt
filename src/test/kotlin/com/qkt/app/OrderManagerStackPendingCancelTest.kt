package com.qkt.app

import com.qkt.app.OrderManagerStackFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.At
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StackPlan
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerStackPendingCancelTest {
    @Test
    fun `layer 1 SL fires and pending layers are cancelled`() {
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
                        LayerSpec(
                            2,
                            SizeQty(NumLit(BigDecimal("0.1"))),
                            com.qkt.dsl.ast.Market,
                            At(
                                BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("100"))),
                                StackDirection.TRADE_DIRECTION,
                            ),
                        ),
                        LayerSpec(
                            3,
                            SizeQty(NumLit(BigDecimal("0.1"))),
                            com.qkt.dsl.ast.Market,
                            At(
                                BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("200"))),
                                StackDirection.TRADE_DIRECTION,
                            ),
                        ),
                    ),
                outerBracket =
                    BracketAst(
                        stopLoss = ChildBy(NumLit(BigDecimal("50"))),
                    ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-3",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.3"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)
        broker.emitFill(broker.submits[0], price = Money.of("50000"))

        // SL at 49950 fires (tick crosses below stop).
        bus.publish(
            TickEvent(
                tick =
                    Tick(
                        symbol = "BTCUSDT",
                        price = BigDecimal("49950"),
                        timestamp = clock.now(),
                    ),
            ),
        )
        // SL is now SUBMITTED; simulate broker fill of the SL order.
        val slOrder = broker.submits.firstOrNull { it.id == "${req.id}-l1-sl" }
        assertThat(slOrder).isNotNull
        broker.emitFill(slOrder!!, price = BigDecimal("49950"))

        // Pending layers l2 and l3 must be cancelled.
        val pendingRemain =
            manager.activeOrders().filter {
                it.parentClientOrderId == req.id && it.state == OrderState.PENDING
            }
        assertThat(pendingRemain).isEmpty()
    }

    @Test
    fun `external cancel of stack id cancels its pending layers`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val plan =
            StackPlan(
                listOf(
                    LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), com.qkt.dsl.ast.Market, Immediate),
                    LayerSpec(
                        2,
                        SizeQty(NumLit(BigDecimal("0.1"))),
                        com.qkt.dsl.ast.Market,
                        At(
                            BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("100"))),
                            StackDirection.TRADE_DIRECTION,
                        ),
                    ),
                    LayerSpec(
                        3,
                        SizeQty(NumLit(BigDecimal("0.1"))),
                        com.qkt.dsl.ast.Market,
                        At(
                            BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("200"))),
                            StackDirection.TRADE_DIRECTION,
                        ),
                    ),
                ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-c",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.3"),
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
        assertThat(manager.pendingOrders()).hasSize(2)
        manager.cancel("stk-c")
        assertThat(manager.pendingOrders()).isEmpty()
    }
}
