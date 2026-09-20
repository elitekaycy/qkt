package com.qkt.app

import com.qkt.app.OrderManagerStackFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskFrac
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

class OrderManagerStackLayerSpecTest {
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
}
