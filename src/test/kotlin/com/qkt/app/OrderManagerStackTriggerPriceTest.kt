package com.qkt.app

import com.qkt.app.OrderManagerStackFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.events.BrokerEvent
import com.qkt.execution.At
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.OrderRequest
import com.qkt.execution.StackPlan
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerStackTriggerPriceTest {
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
}
