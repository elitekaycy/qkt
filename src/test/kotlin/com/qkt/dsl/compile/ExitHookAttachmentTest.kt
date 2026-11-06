package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.ExitHooksAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackSpacing
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** [ExitHookAttachment]: which hook parents and children v1 accepts, and the stamping of the hook reference. */
class ExitHookAttachmentTest {
    private val one = NumLit(BigDecimal.ONE)
    private val sell = Sell("b", ActionOpts(sizing = SizeQty(one)))
    private val ref = ExitHookRef("def-1", "fp")

    private fun hooked(
        parent: ActionOpts = ActionOpts(sizing = SizeQty(one)),
        child: com.qkt.dsl.ast.ActionAst = sell,
    ) = parent.copy(exitHooks = ExitHooksAst(onStop = listOf(child)))

    @Test
    fun `an OCO parent cannot carry exit hooks`() {
        val oco = OcoAst(ChildAt(one), ChildAt(one))

        assertThatThrownBy {
            ExitHookAttachment.validate(
                hooked(parent = ActionOpts(sizing = SizeQty(one), oco = oco)),
            )
        }.hasMessageContaining("OCO and STACK_AT are not supported")
    }

    @Test
    fun `hook children must be plain BUY, SELL or LOG orders`() {
        val stacked = Sell("b", ActionOpts(sizing = SizeQty(one), stack = StackSpacing(2, one, StackDirection.ABOVE)))

        assertThatThrownBy { ExitHookAttachment.validate(hooked(child = Close("b"))) }
            .hasMessageContaining("Exit-hook children must be BUY, SELL, or LOG actions; got Close")
        assertThatThrownBy { ExitHookAttachment.validate(hooked(child = stacked)) }
            .hasMessageContaining("Exit-hook children may carry a BRACKET but not OCO, STACK, or STACK_AT in v1.")
    }

    @Test
    fun `every order the parent emits carries the hook reference`() {
        val one = BigDecimal.ONE
        val ctx =
            EvalContext(
                Candle("S", one, one, one, one, BigDecimal.ZERO, 0L, 1L),
                emptyMap(),
                emptyMap(),
                testStrategyContext(),
            )
        val submit = Signal.Submit(OrderRequest.Market("o", "S", Side.BUY, one, TimeInForce.GTC, 0L))
        val stamped = ExitHookAttachment.attach({ listOf(Signal.Sell("S", one), submit) }, ref)(ctx)

        assertThat(stamped).containsExactly(Signal.Sell("S", one, exitHook = ref), submit.copy(exitHook = ref))
        assertThatThrownBy { ExitHookAttachment.attach({ listOf(Signal.CancelPendingForSymbol("S")) }, ref)(ctx) }
            .hasMessageContaining("Exit hooks require an order-producing BUY/SELL action")
    }
}
