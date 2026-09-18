package com.qkt.app

import com.qkt.app.OrderManagerStackFixtures.newBus
import com.qkt.app.OrderManagerStackFixtures.publishLayerFill
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
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
import com.qkt.execution.StackPlan
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerStackFlatTest {
    @Test
    fun `multi-layer flat detection requires all positions closed`() {
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
                    ),
                outerBracket =
                    BracketAst(
                        stopLoss = ChildBy(NumLit(BigDecimal("50"))),
                    ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-mflat",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.2"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)
        broker.emitFill(broker.submits[0], price = Money.of("50000"))

        // Layer 2 triggers and fills.
        bus.publish(
            TickEvent(
                tick = Tick("BTCUSDT", BigDecimal("50100"), clock.now()),
            ),
        )
        val l2Submit = broker.submits.first { it.id == "${req.id}-l2" }
        broker.emitFill(l2Submit, price = BigDecimal("50100"))

        // Both SLs are now pending. Fire layer 1's SL directly to avoid tick triggering layer 2's SL.
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${req.id}-l1-sl",
                brokerOrderId = "${req.id}-l1-sl",
                symbol = "BTCUSDT",
                side = Side.SELL,
                price = BigDecimal("49950"),
                quantity = BigDecimal("0.1"),
                timestamp = clock.now(),
            ),
        )

        // Stack NOT terminated yet — layer 2's SL is still alive.
        assertThat(manager.activeOrders().any { it.id == "${req.id}-l2-sl" }).isTrue

        // Now fire layer 2's SL directly — stack should terminate.
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${req.id}-l2-sl",
                brokerOrderId = "${req.id}-l2-sl",
                symbol = "BTCUSDT",
                side = Side.SELL,
                price = BigDecimal("50050"),
                quantity = BigDecimal("0.1"),
                timestamp = clock.now(),
            ),
        )

        // Both SL orders are now terminal; no pending or working child orders remain.
        assertThat(manager.activeOrders().none { it.id == "${req.id}-l1-sl" }).isTrue
        assertThat(manager.activeOrders().none { it.id == "${req.id}-l2-sl" }).isTrue
        assertThat(manager.activeOrders().none { it.id == "${req.id}-l1" }).isTrue
        assertThat(manager.activeOrders().none { it.id == "${req.id}-l2" }).isTrue
    }

    @Test
    fun `POSITION_MODIFY closes terminate the stack and cancel venue pending tiers`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(
                    OrderTypeCapability.MARKET,
                    OrderTypeCapability.LIMIT,
                    OrderTypeCapability.STOP,
                    OrderTypeCapability.POSITION_MODIFY,
                ),
            )
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
                outerBracket = BracketAst(stopLoss = ChildBy(NumLit(BigDecimal("50")))),
            )
        val stack =
            OrderRequest.Stack(
                id = "stk-position-close",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.3"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(stack)

        publishLayerFill(bus, stack, layer = 1, ticket = "TKT-1", quantity = BigDecimal("0.1"), clock = clock)
        publishLayerFill(bus, stack, layer = 2, ticket = "TKT-2", quantity = BigDecimal("0.1"), clock = clock)
        assertThat(broker.modifyPositions).hasSize(2)

        publishLayerClose(bus, stack, layer = 1, ticket = "TKT-1", quantity = BigDecimal("0.1"), clock = clock)
        publishLayerClose(bus, stack, layer = 2, ticket = "TKT-2", quantity = BigDecimal("0.04"), clock = clock)
        assertThat(broker.cancels).isEmpty()

        publishLayerClose(bus, stack, layer = 2, ticket = "TKT-2", quantity = BigDecimal("0.06"), clock = clock)

        assertThat(broker.modifyPositions).hasSize(2)
        assertThat(broker.cancels).containsExactly("${stack.id}-l3")
        assertThat(manager.pendingStackLayerInfos()).isEmpty()
    }

    private fun publishLayerClose(
        bus: EventBus,
        stack: OrderRequest.Stack,
        layer: Int,
        ticket: String,
        quantity: BigDecimal,
        clock: FixedClock,
    ) {
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${stack.id}-l$layer",
                brokerOrderId = ticket,
                symbol = stack.symbol,
                side = if (stack.side == Side.BUY) Side.SELL else Side.BUY,
                price = BigDecimal("49950"),
                quantity = quantity,
                timestamp = clock.now(),
            ),
        )
    }
}
