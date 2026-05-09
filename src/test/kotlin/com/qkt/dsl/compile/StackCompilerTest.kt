package com.qkt.dsl.compile

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
import com.qkt.execution.At
import com.qkt.execution.Immediate
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StackCompilerTest {
    @Test
    fun `SPACING form folds into N layers`() {
        val ast = StackSpacing(3, NumLit(BigDecimal("100")), StackDirection.TRADE_DIRECTION)
        val plan = StackCompiler.compile(ast, outerSizing = SizeQty(NumLit(BigDecimal("0.1"))), outerBracket = null)
        assertThat(plan.layers).hasSize(3)
        assertThat(plan.layers[0].trigger).isEqualTo(Immediate)
        assertThat(plan.layers[1].trigger).isInstanceOf(At::class.java)
        val at1 = plan.layers[1].trigger as At
        // entry + 100 * 1
        val expected1 = BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("100")))
        assertThat(at1.price).isEqualTo(expected1)
        val at2 = plan.layers[2].trigger as At
        val expected2 = BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("200")))
        assertThat(at2.price).isEqualTo(expected2)
    }

    @Test
    fun `layer-list form preserves explicit triggers and order types`() {
        val l1 = StackLayer(SizeQty(NumLit(BigDecimal("0.1"))))
        val l2 =
            StackLayer(
                SizeQty(NumLit(BigDecimal("0.2"))),
                at =
                    BinaryOp(
                        BinOp.ADD,
                        StackEntryRef,
                        NumLit(BigDecimal("100")),
                    ),
            )
        val l3 =
            StackLayer(
                SizeQty(NumLit(BigDecimal("0.3"))),
                orderType = Limit(NumLit(BigDecimal("50100"))),
                at = NumLit(BigDecimal("50100")),
            )
        val plan = StackCompiler.compile(StackLayers(listOf(l1, l2, l3)), outerSizing = null, outerBracket = null)
        assertThat(plan.layers[0].trigger).isEqualTo(Immediate)
        assertThat(plan.layers[0].orderType).isEqualTo(Market)
        assertThat(plan.layers[2].orderType).isInstanceOf(Limit::class.java)
    }
}
