package com.qkt.app

import com.qkt.app.OrderManagerStackFixtures.newBus
import com.qkt.app.OrderManagerStackFixtures.publishLayerFill
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
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

class OrderManagerStackProtectionFailureTest {
    @Test
    fun `failed venue protection arms an engine-held close-by-ticket stop and alerts`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.POSITION_MODIFY),
            ).apply { rejectPositionModifications = true }
        val alerts = mutableListOf<String>()
        val manager =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                onProtectionFailure = { _, message -> alerts.add(message) },
            )
        val plan =
            StackPlan(
                layers = listOf(LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), com.qkt.dsl.ast.Market, Immediate)),
                outerBracket = BracketAst(stopLoss = ChildBy(NumLit(BigDecimal("50")))),
            )
        val stack =
            OrderRequest.Stack(
                id = "stk-fallback",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
                strategyId = "alpha",
            )
        manager.submit(stack)
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${stack.id}-l1",
                brokerOrderId = "TKT-42",
                symbol = stack.symbol,
                side = stack.side,
                price = BigDecimal("50000"),
                quantity = stack.quantity,
                strategyId = stack.strategyId,
                timestamp = clock.now(),
            ),
        )

        val fallback = manager.getOrder("${stack.id}-l1-sl")
        assertThat(fallback?.state).isEqualTo(OrderState.PENDING)
        assertThat((fallback?.request as OrderRequest.Stop).stopPrice).isEqualByComparingTo("49950")
        assertThat(alerts.single()).contains("engine-held stop armed at 49950")

        manager.cancelEntriesForHalt("alpha")
        assertThat(manager.getOrder("${stack.id}-l1-sl")?.state).isEqualTo(OrderState.PENDING)

        bus.publish(TickEvent(Tick("BTCUSDT", BigDecimal("49940"), clock.now())))

        val close = broker.submits.last() as OrderRequest.Market
        assertThat(close.closesTicket).isEqualTo("TKT-42")
    }

    @Test
    fun `deferred venue protection does not block fill and handles later rejection`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.POSITION_MODIFY),
            ).apply { deferPositionModifications = true }
        val alerts = mutableListOf<String>()
        val manager =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                onProtectionFailure = { _, message -> alerts.add(message) },
            )
        val stack =
            OrderRequest.Stack(
                id = "stk-async-protection",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                plan =
                    StackPlan(
                        layers =
                            listOf(
                                LayerSpec(
                                    1,
                                    SizeQty(NumLit(BigDecimal("0.1"))),
                                    com.qkt.dsl.ast.Market,
                                    Immediate,
                                ),
                            ),
                        outerBracket = BracketAst(stopLoss = ChildBy(NumLit(BigDecimal("50")))),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
                strategyId = "alpha",
            )
        manager.submit(stack)

        publishLayerFill(bus, stack, layer = 1, ticket = "TKT-42", quantity = stack.quantity, clock = clock)

        assertThat(broker.modifyPositions).hasSize(1)
        assertThat(manager.getOrder("${stack.id}-l1-sl")).isNull()
        assertThat(alerts).isEmpty()

        broker.completeNextPositionModification(false, "gateway timeout")

        assertThat(manager.getOrder("${stack.id}-l1-sl")?.state).isEqualTo(OrderState.PENDING)
        assertThat(alerts.single()).contains("gateway timeout", "engine-held stop armed")
    }

    @Test
    fun `blank stack fill ticket raises a protection failure alert`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.POSITION_MODIFY),
            )
        val alerts = mutableListOf<String>()
        val manager =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                onProtectionFailure = { _, message -> alerts.add(message) },
            )
        val stack =
            OrderRequest.Stack(
                id = "stk-no-ticket",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                plan =
                    StackPlan(
                        layers =
                            listOf(
                                LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), com.qkt.dsl.ast.Market, Immediate),
                            ),
                        outerBracket = BracketAst(stopLoss = ChildBy(NumLit(BigDecimal("50")))),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
                strategyId = "alpha",
            )
        manager.submit(stack)

        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${stack.id}-l1",
                brokerOrderId = "",
                symbol = stack.symbol,
                side = stack.side,
                price = BigDecimal("50000"),
                quantity = stack.quantity,
                strategyId = stack.strategyId,
                timestamp = clock.now(),
            ),
        )

        assertThat(alerts.single()).contains("has no venue ticket")
        assertThat(broker.modifyPositions).isEmpty()
    }
}
