package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StackAtCompilerTest {
    private fun clause(
        threshold: com.qkt.dsl.ast.ExprAst = NumLit(BigDecimal("0.005")),
        withinMs: Long = 30 * 60 * 1000L,
        sizing: com.qkt.dsl.ast.SizingAst = SizeQty(NumLit(BigDecimal("0.05"))),
        bracket: BracketAst =
            BracketAst(
                stopLoss = ChildBy(NumLit(BigDecimal("0.005"))),
                takeProfit = ChildBy(NumLit(BigDecimal("0.020"))),
            ),
    ) = StackAtClause(
        mfeThreshold = threshold,
        withinDuration = DurationAst(withinMs),
        sizing = sizing,
        bracket = bracket,
    )

    @Test
    fun `compiles a simple literal clause to a CompiledStackTier`() {
        val tier = StackAtCompiler.compile(clause())
        assertThat(tier.mfeThreshold).isEqualByComparingTo("0.005")
        assertThat(tier.withinMs).isEqualTo(30 * 60 * 1000L)
        assertThat(tier.stackQuantity).isEqualByComparingTo("0.05")
        assertThat(tier.slDistance).isEqualByComparingTo("0.005")
        assertThat(tier.tpDistance).isEqualByComparingTo("0.020")
    }

    @Test
    fun `constant-folds arithmetic expressions in the threshold`() {
        val tier =
            StackAtCompiler.compile(
                clause(threshold = BinaryOp(BinOp.MUL, NumLit(BigDecimal("10")), NumLit(BigDecimal("5")))),
            )
        assertThat(tier.mfeThreshold).isEqualByComparingTo("50")
    }

    @Test
    fun `unary negation is supported in constant folding`() {
        val tier =
            StackAtCompiler.compile(
                clause(threshold = UnaryOp(UnOp.NEG, NumLit(BigDecimal("0.005")))),
            )
        assertThat(tier.mfeThreshold).isEqualByComparingTo("-0.005")
    }

    @Test
    fun `compileAll preserves order`() {
        val tiers =
            StackAtCompiler.compileAll(
                listOf(
                    clause(threshold = NumLit(BigDecimal("10"))),
                    clause(threshold = NumLit(BigDecimal("20"))),
                    clause(threshold = NumLit(BigDecimal("30"))),
                ),
            )
        assertThat(tiers).hasSize(3)
        assertThat(tiers[0].mfeThreshold).isEqualByComparingTo("10")
        assertThat(tiers[1].mfeThreshold).isEqualByComparingTo("20")
        assertThat(tiers[2].mfeThreshold).isEqualByComparingTo("30")
    }

    @Test
    fun `non-constant threshold expression is rejected at compile time`() {
        assertThatThrownBy { StackAtCompiler.compile(clause(threshold = Ref("foo"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("STACK_AT MFE threshold")
            .hasMessageContaining("compile-time constant")
    }

    @Test
    fun `non-SizeQty sizing form is rejected`() {
        assertThatThrownBy {
            StackAtCompiler.compile(clause(sizing = SizeNotional(NumLit(BigDecimal("100")))))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("STACK_AT only supports literal SIZING")
    }

    @Test
    fun `bracket without STOP LOSS is rejected`() {
        assertThatThrownBy {
            StackAtCompiler.compile(clause(bracket = BracketAst(takeProfit = ChildBy(NumLit(BigDecimal("0.020"))))))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("requires STOP LOSS")
    }

    @Test
    fun `bracket without TAKE PROFIT is rejected`() {
        assertThatThrownBy {
            StackAtCompiler.compile(clause(bracket = BracketAst(stopLoss = ChildBy(NumLit(BigDecimal("0.005"))))))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("requires TAKE PROFIT")
    }

    @Test
    fun `bracket with non-BY child price form is rejected`() {
        assertThatThrownBy {
            StackAtCompiler.compile(
                clause(
                    bracket =
                        BracketAst(
                            stopLoss = ChildAt(NumLit(BigDecimal("1.0950"))),
                            takeProfit = ChildBy(NumLit(BigDecimal("0.020"))),
                        ),
                ),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("STOP LOSS must use BY")
    }
}
