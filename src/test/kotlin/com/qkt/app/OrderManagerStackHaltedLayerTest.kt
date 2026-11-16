package com.qkt.app

import com.qkt.app.OrderManagerStackFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.events.RiskRejectedEvent
import com.qkt.execution.At
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StackPlan
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerStackHaltedLayerTest {
    private val stack =
        OrderRequest.Stack(
            id = "stk",
            symbol = "BTCUSDT",
            side = Side.BUY,
            quantity = BigDecimal("0.3"),
            plan =
                StackPlan(
                    listOf(
                        LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), Market, Immediate),
                        LayerSpec(
                            2,
                            SizeQty(NumLit(BigDecimal("0.2"))),
                            Market,
                            At(
                                BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("100"))),
                                StackDirection.TRADE_DIRECTION,
                            ),
                        ),
                    ),
                ),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    @Test
    fun `a layer that materialises while its strategy is halted is rejected and never sent`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        var halted = false
        val manager =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                engineHeldSubmissionBlockReason = { if (halted) "halted: daily loss" else null },
            )
        val refused = mutableListOf<RiskRejectedEvent>()
        bus.subscribe<RiskRejectedEvent> { refused += it }

        manager.submit(stack)
        halted = true
        broker.emitFill(broker.submits.single(), price = Money.of("50000"))

        assertThat(manager.getOrder("stk-l2")!!.state).isEqualTo(OrderState.REJECTED)
        assertThat(refused.single().request.id).isEqualTo("stk-l2")
        assertThat(refused.single().reason).isEqualTo("halted: daily loss")
        assertThat(broker.submits.map { it.id }).containsExactly("stk-l1")
        assertThat(manager.pendingStackLayerInfos()).isEmpty()
        assertThat(manager.quantityFor("BTCUSDT", Side.BUY, "alpha")).isEqualByComparingTo("0")
    }
}
