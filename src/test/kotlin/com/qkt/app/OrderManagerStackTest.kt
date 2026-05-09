package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
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

class OrderManagerStackTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `layer 1 fires immediately, layers 2 and 3 pend until trigger`() {
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
                id = "stk-1",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.3"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)

        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits[0]).isInstanceOf(OrderRequest.Market::class.java)

        broker.emitFill(broker.submits[0], price = Money.of("50000"))

        val pending = manager.pendingOrders()
        assertThat(pending).hasSize(2)
        val triggers = pending.map { (it.request as OrderRequest.Stop).stopPrice }
        assertThat(triggers)
            .usingElementComparator(Comparator { a, b -> a.compareTo(b) })
            .containsExactlyInAnyOrder(BigDecimal("50100"), BigDecimal("50200"))
    }

    @Test
    fun `tick crossing layer 2 trigger fires the Stop`() {
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
                ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-2",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.2"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)
        broker.emitFill(broker.submits[0], price = Money.of("50000"))
        broker.submits.clear()

        bus.publish(
            TickEvent(
                tick =
                    Tick(
                        symbol = "BTCUSDT",
                        price = BigDecimal("50100"),
                        timestamp = clock.now(),
                    ),
            ),
        )

        assertThat(broker.submits).anyMatch { it.id == "${req.id}-l2" }
    }

    @Test
    fun `layer 2 fill attaches its own SL`() {
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
                id = "stk-l2sl",
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

        // Both layer 1 and layer 2 should have SL orders attached.
        assertThat(manager.activeOrders().any { it.id == "${req.id}-l1-sl" }).isTrue
        assertThat(manager.activeOrders().any { it.id == "${req.id}-l2-sl" }).isTrue
    }

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
