package com.qkt.app

import com.qkt.common.Side
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.execution.At
import com.qkt.execution.ExpiryAction
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.OrderRequest
import com.qkt.execution.StackPlan
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExitHookRequestIdsTest {
    private val market =
        OrderRequest.Market(
            id = "m1",
            symbol = "BTCUSDT",
            side = Side.BUY,
            quantity = BigDecimal.ONE,
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    @Test
    fun `a stack fills under one entry, stop and target id per layer`() {
        val size = SizeQty(NumLit(BigDecimal("0.1")))
        val stack =
            OrderRequest.Stack(
                id = "stk",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.2"),
                plan =
                    StackPlan(
                        listOf(
                            LayerSpec(1, size, Market, Immediate),
                            LayerSpec(2, size, Market, At(NumLit(BigDecimal("50100")), StackDirection.TRADE_DIRECTION)),
                        ),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )

        val ids = exitHookRequestIds(stack)

        assertThat(ids.entries).containsExactly("stk-l1", "stk-l2")
        assertThat(ids.stops).containsExactly("stk-l1-sl", "stk-l2-sl")
        assertThat(ids.takeProfits).containsExactly("stk-l1-tp", "stk-l2-tp")
    }

    @Test
    fun `a plain order is its own entry and has no exit ids`() {
        val ids = exitHookRequestIds(market)

        assertThat(ids.entries).containsExactly("m1")
        assertThat(ids.stops).isEmpty()
        assertThat(ids.takeProfits).isEmpty()
    }

    @Test
    fun `a bracket fills under its own id or its entry's, with suffixed exits`() {
        val ids = exitHookRequestIds(OrderManagerFixtures.bracket("b1", "b1-entry"))

        assertThat(ids.entries).containsExactly("b1", "b1-entry")
        assertThat(ids.stops).containsExactly("b1-sl")
        assertThat(ids.takeProfits).containsExactly("b1-tp")
    }

    @Test
    fun `a timed exit parent cannot carry an exit hook`() {
        val timed =
            OrderRequest.TimeExit(
                id = "t1",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                target = market,
                deadline = Instant.ofEpochMilli(60_000L),
                onExpiry = ExpiryAction.CANCEL,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )

        assertThatThrownBy { exitHookRequestIds(timed) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Exit hooks do not support TimeExit parents in v1")
    }
}
