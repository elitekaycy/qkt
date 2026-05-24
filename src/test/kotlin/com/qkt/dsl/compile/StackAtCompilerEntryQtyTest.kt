package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.EntryQty
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StreamFieldRef
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StackAtCompilerEntryQtyTest {
    private val baseBracket =
        BracketAst(
            stopLoss = ChildBy(NumLit(BigDecimal("2.0"))),
            takeProfit = ChildBy(NumLit(BigDecimal("4.0"))),
        )

    private fun clause(sizingExpr: com.qkt.dsl.ast.ExprAst): StackAtClause =
        StackAtClause(
            mfeThreshold = NumLit(BigDecimal("5")),
            withinDuration = DurationAst(millis = 30 * 60_000L),
            sizing = SizeQty(sizingExpr),
            bracket = baseBracket,
        )

    @Test
    fun `ENTRY_QTY alone resolves to parentQty`() {
        val tier = StackAtCompiler.compile(clause(EntryQty))
        assertThat(tier.resolveStackQuantity(BigDecimal("0.40")))
            .isEqualByComparingTo(BigDecimal("0.40"))
    }

    @Test
    fun `scaled fraction of ENTRY_QTY`() {
        val expr = BinaryOp(BinOp.MUL, NumLit(BigDecimal("0.3")), EntryQty)
        val tier = StackAtCompiler.compile(clause(expr))
        assertThat(tier.resolveStackQuantity(BigDecimal("0.40")))
            .isEqualByComparingTo(BigDecimal("0.12"))
    }

    @Test
    fun `affine combination of ENTRY_QTY`() {
        val expr =
            BinaryOp(
                BinOp.ADD,
                BinaryOp(BinOp.MUL, NumLit(BigDecimal("0.5")), EntryQty),
                NumLit(BigDecimal("0.05")),
            )
        val tier = StackAtCompiler.compile(clause(expr))
        assertThat(tier.resolveStackQuantity(BigDecimal("0.30")))
            .isEqualByComparingTo(BigDecimal("0.20"))
    }

    @Test
    fun `division rounds via Money SCALE`() {
        val expr = BinaryOp(BinOp.DIV, EntryQty, NumLit(BigDecimal("3")))
        val tier = StackAtCompiler.compile(clause(expr))
        val resolved = tier.resolveStackQuantity(BigDecimal("1"))
        assertThat(resolved.scale()).isEqualTo(Money.SCALE)
        assertThat(resolved).isLessThan(BigDecimal("0.34"))
        assertThat(resolved).isGreaterThan(BigDecimal("0.33"))
    }

    @Test
    fun `pure literal still works as a regression`() {
        val tier = StackAtCompiler.compile(clause(NumLit(BigDecimal("0.10"))))
        assertThat(tier.resolveStackQuantity(BigDecimal("999")))
            .isEqualByComparingTo(BigDecimal("0.10"))
    }

    @Test
    fun `ENTRY_QTY in mfeThreshold fails compile`() {
        val c =
            StackAtClause(
                mfeThreshold = EntryQty,
                withinDuration = DurationAst(millis = 30 * 60_000L),
                sizing = SizeQty(NumLit(BigDecimal("0.10"))),
                bracket = baseBracket,
            )
        assertThatThrownBy { StackAtCompiler.compile(c) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("STACK_AT MFE threshold")
    }

    @Test
    fun `Ref or StreamFieldRef in sizing fails compile with the unified message`() {
        val ref = clause(Ref("foo"))
        assertThatThrownBy { StackAtCompiler.compile(ref) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("STACK_AT SIZING must use literals, ENTRY_QTY, and arithmetic only")

        val stream = clause(StreamFieldRef("gold", "close"))
        assertThatThrownBy { StackAtCompiler.compile(stream) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("STACK_AT SIZING must use literals, ENTRY_QTY, and arithmetic only")
    }
}
