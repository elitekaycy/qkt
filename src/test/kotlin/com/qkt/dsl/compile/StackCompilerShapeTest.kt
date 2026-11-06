package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.execution.At
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** [StackCompiler] layer geometry for expression spacings, short layer lists and missing sizing. */
class StackCompilerShapeTest {
    private val size = SizeQty(NumLit(BigDecimal("0.1")))
    private val atr = StreamFieldRef("b", "high")

    @Test
    fun `an expression spacing scales each layer's offset by its distance from the entry`() {
        val plan = StackCompiler.compile(StackSpacing(3, atr, StackDirection.BELOW), size, null, Side.BUY)
        val third = plan.layers[2]

        assertThat((third.trigger as At).price)
            .isEqualTo(BinaryOp(BinOp.SUB, StackEntryRef, BinaryOp(BinOp.MUL, atr, NumLit(BigDecimal(2)))))
        assertThat(third.orderType).isInstanceOf(Limit::class.java)
    }

    @Test
    fun `a short layer list triggers below the entry`() {
        val layers = StackLayers(listOf(StackLayer(size), StackLayer(size, at = StackEntryRef)))

        val plan = StackCompiler.compile(layers, null, null, Side.SELL)

        assertThat((plan.layers[1].trigger as At).direction).isEqualTo(StackDirection.BELOW)
        assertThat(plan.layers[1].orderType).isEqualTo(Market)
    }

    @Test
    fun `a spacing stack needs the entry's own sizing`() {
        assertThatThrownBy { StackCompiler.compile(StackSpacing(2, atr, StackDirection.ABOVE), null, null, Side.BUY) }
            .hasMessageContaining("STACK SPACING form requires outer SIZING on the BUY/SELL action")
    }
}
