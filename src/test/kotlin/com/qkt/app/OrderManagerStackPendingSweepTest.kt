package com.qkt.app

import com.qkt.app.OrderManagerStackFixtures.newBus
import com.qkt.app.OrderManagerStackFixtures.publishLayerFill
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
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

class OrderManagerStackPendingSweepTest {
    @Test
    fun `WITHIN deadline cancels pending layers`() {
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
                withinMillis = 60_000L,
            )
        val req =
            OrderRequest.Stack(
                id = "stk-w",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.2"),
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
        assertThat(manager.pendingOrders()).hasSize(1)

        // Advance simulated clock past deadline and tick.
        clock.time += 60_001L
        bus.publish(
            TickEvent(
                tick = Tick("BTCUSDT", BigDecimal("50050"), clock.now()),
            ),
        )
        assertThat(manager.pendingOrders()).isEmpty()
    }

    @Test
    fun `stack without WITHIN cancels pending layers at the safety deadline`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP),
            )
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
                ),
            )
        val stack =
            OrderRequest.Stack(
                id = "stk-default-deadline",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.2"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(stack)
        publishLayerFill(bus, stack, layer = 1, ticket = "TKT-1", quantity = BigDecimal("0.1"), clock = clock)

        clock.time += StackTracker.DEFAULT_PENDING_LIFETIME_MS + 1L
        bus.publish(TickEvent(Tick("BTCUSDT", BigDecimal("50050"), clock.now())))

        assertThat(broker.cancels).containsExactly("${stack.id}-l2")
        assertThat(manager.pendingStackLayerInfos()).isEmpty()
    }

    @Test
    fun `cancelPendingForSymbol cancels all stacks targeting that symbol`() {
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
                            StackDirection.ABOVE,
                        ),
                    ),
                ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-cs",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.2"),
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
        assertThat(manager.pendingOrders()).isNotEmpty
        manager.cancelPendingForSymbol("BTCUSDT")
        assertThat(manager.pendingOrders()).isEmpty()
    }
}
