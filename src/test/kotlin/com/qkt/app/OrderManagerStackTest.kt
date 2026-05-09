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
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
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
}
