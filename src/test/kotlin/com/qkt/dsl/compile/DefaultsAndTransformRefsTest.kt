package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.kotlin.SYMBOL_PLACEHOLDER_NAME
import com.qkt.dsl.stdlib.FuncArity
import com.qkt.dsl.stdlib.FuncRegistry
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** DEFAULTS keep non-placeholder refs, a transform rewrites refs inside a stack layer, and function arities. */
class DefaultsAndTransformRefsTest {
    private val one = NumLit(BigDecimal.ONE)

    @Test
    fun `a DEFAULTS stop that names a LET keeps the ref while SYMBOL becomes the action's candle`() {
        val defaults =
            DefaultsBlock(
                stopLoss = ChildBy(BinaryOp(BinOp.MUL, Ref("dist"), Ref(SYMBOL_PLACEHOLDER_NAME))),
                takeProfit = ChildBy(one),
            )

        val merged = mergeDefaults(Buy("btc", ActionOpts(sizing = SizeQty(one))), defaults) as Buy

        assertThat(merged.opts.bracket?.stopLoss)
            .isEqualTo(ChildBy(BinaryOp(BinOp.MUL, Ref("dist"), StreamFieldRef("btc", "candle"))))
    }

    @Test
    fun `a transform rewrites refs in a stack layer's sizing, order price and trigger`() {
        val inline = ExprTransform { ref -> if (ref.name == "k") NumLit(BigDecimal("2")) else ref }
        val layer = StackLayer(SizeQty(Ref("k")), Limit(Ref("k")), Ref("k"))

        assertThat(
            inline.stackLayer(layer),
        ).isEqualTo(
            StackLayer(SizeQty(NumLit(BigDecimal("2"))), Limit(NumLit(BigDecimal("2"))), NumLit(BigDecimal("2"))),
        )
    }

    @Test
    fun `function arities are reported for editor tooling and unknown names have none`() {
        assertThat(FuncRegistry.arity("abs")).isEqualTo(FuncArity(1, variadic = false))
        assertThat(FuncRegistry.arity("pow")).isEqualTo(FuncArity(2, variadic = false))
        assertThat(FuncRegistry.arity("max")).isEqualTo(FuncArity(2, variadic = true))
        assertThat(FuncRegistry.arity("nope")).isNull()
    }
}
