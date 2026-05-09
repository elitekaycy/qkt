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
import com.qkt.dsl.ast.SizeRiskFrac
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

    @Test
    fun `pendingStackLayerInfos exposes pending layers with trigger and qty`() {
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
                        SizeQty(NumLit(BigDecimal("0.2"))),
                        com.qkt.dsl.ast.Market,
                        At(
                            BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("100"))),
                            StackDirection.TRADE_DIRECTION,
                        ),
                    ),
                    LayerSpec(
                        3,
                        SizeQty(NumLit(BigDecimal("0.3"))),
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
                id = "stk-info",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.6"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)
        // Fill layer 1 so layers 2 and 3 become PENDING.
        broker.emitFill(broker.submits[0], price = Money.of("50000"))

        val infos = manager.pendingStackLayerInfos()
        assertThat(infos).hasSize(2)

        val byLayer = infos.associateBy { it.layer }

        val l2 = byLayer[2]!!
        assertThat(l2.stackId).isEqualTo("stk-info")
        assertThat(l2.side).isEqualTo("BUY")
        assertThat(l2.triggerPrice).isEqualByComparingTo(BigDecimal("50100"))
        assertThat(l2.quantity).isEqualByComparingTo(BigDecimal("0.2"))

        val l3 = byLayer[3]!!
        assertThat(l3.stackId).isEqualTo("stk-info")
        assertThat(l3.side).isEqualTo("BUY")
        assertThat(l3.triggerPrice).isEqualByComparingTo(BigDecimal("50200"))
        assertThat(l3.quantity).isEqualByComparingTo(BigDecimal("0.3"))
    }

    @Test
    fun `SELL stack triggers fire at decreasing prices`() {
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
                            BinaryOp(BinOp.SUB, StackEntryRef, NumLit(BigDecimal("100"))),
                            StackDirection.BELOW,
                        ),
                    ),
                    LayerSpec(
                        3,
                        SizeQty(NumLit(BigDecimal("0.1"))),
                        com.qkt.dsl.ast.Market,
                        At(
                            BinaryOp(BinOp.SUB, StackEntryRef, NumLit(BigDecimal("200"))),
                            StackDirection.BELOW,
                        ),
                    ),
                ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-sell",
                symbol = "BTCUSDT",
                side = Side.SELL,
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
                side = Side.SELL,
                price = BigDecimal("50000"),
                quantity = BigDecimal("0.1"),
                timestamp = clock.now(),
            ),
        )
        val pending = manager.pendingOrders()
        assertThat(pending).hasSize(2)
        val triggers = pending.map { (it.request as OrderRequest.Stop).stopPrice }
        assertThat(triggers)
            .usingElementComparator(Comparator { a, b -> a.compareTo(b) })
            .containsExactlyInAnyOrder(
                BigDecimal("49900"),
                BigDecimal("49800"),
            )
    }

    @Test
    fun `layer 1 with explicit LIMIT AT pends until trigger then fills as anchor`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val plan =
            StackPlan(
                listOf(
                    LayerSpec(
                        1,
                        SizeQty(NumLit(BigDecimal("0.1"))),
                        com.qkt.dsl.ast
                            .Limit(NumLit(BigDecimal("50000"))),
                        At(NumLit(BigDecimal("50000")), StackDirection.ABOVE),
                    ),
                    LayerSpec(
                        2,
                        SizeQty(NumLit(BigDecimal("0.1"))),
                        com.qkt.dsl.ast.Market,
                        At(BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("100"))), StackDirection.ABOVE),
                    ),
                ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-l1at",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.2"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)

        // Layer 1 should be a Limit order at 50000.
        assertThat(broker.submits).hasSize(1)
        val l1 = broker.submits[0]
        assertThat(l1).isInstanceOf(OrderRequest.Limit::class.java)
        assertThat((l1 as OrderRequest.Limit).limitPrice).isEqualByComparingTo(BigDecimal("50000"))

        // Simulate layer 1 fill at 50000.
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
        // Layer 2 should now be PENDING with trigger 50100.
        val pending = manager.pendingOrders()
        assertThat(pending).hasSize(1)
        assertThat((pending[0].request as OrderRequest.Stop).stopPrice).isEqualByComparingTo(BigDecimal("50100"))
    }

    @Test
    fun `layer 1 AT expression referencing entry throws on submit`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val plan =
            StackPlan(
                listOf(
                    LayerSpec(
                        1,
                        SizeQty(NumLit(BigDecimal("0.1"))),
                        com.qkt.dsl.ast
                            .Limit(NumLit(BigDecimal("50000"))),
                        At(BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("0"))), StackDirection.ABOVE),
                    ),
                ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-bad",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        org.assertj.core.api.Assertions
            .assertThatThrownBy { manager.submit(req) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("entry")
    }

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

    @Test
    fun `resolvedQuantity on LayerSpec is used directly, bypassing SizeQty literal fallback`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)

        // SizeRiskFrac would normally error in OrderManager's literal fallback,
        // but resolvedQuantity short-circuits to the pre-resolved value.
        val preResolved = BigDecimal("0.05")
        val plan =
            StackPlan(
                listOf(
                    LayerSpec(
                        index = 1,
                        sizing = SizeRiskFrac(NumLit(BigDecimal("0.01"))),
                        orderType = com.qkt.dsl.ast.Market,
                        trigger = Immediate,
                        resolvedQuantity = preResolved,
                    ),
                ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-resolved",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = preResolved,
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)

        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits[0].quantity).isEqualByComparingTo(preResolved)
    }

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
