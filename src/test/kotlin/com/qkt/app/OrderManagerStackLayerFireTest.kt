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

class OrderManagerStackLayerFireTest {
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
}
