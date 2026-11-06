package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.EntryQty
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** STACK_AT sizing and bracket distances fold literal arithmetic and refuse boolean operators. */
class StackAtCompilerArithmeticTest {
    private fun num(v: String) = NumLit(BigDecimal(v))

    private fun op(
        o: BinOp,
        l: ExprAst,
        r: ExprAst,
    ) = BinaryOp(o, l, r)

    private fun clause(
        sizing: ExprAst = num("1"),
        stopLoss: ExprAst = num("2"),
    ) = StackAtClause(num("10"), DurationAst(60_000L), SizeQty(sizing), BracketAst(ChildBy(stopLoss), ChildBy(num("20"))))

    @Test
    fun `sizing subtracts and negates relative to the parent quantity`() {
        val tier = StackAtCompiler.compile(clause(sizing = op(BinOp.SUB, EntryQty, UnaryOp(UnOp.NEG, num("1")))))

        assertThat(tier.resolveStackQuantity(BigDecimal("3"))).isEqualByComparingTo("4")
    }

    @Test
    fun `bracket distances fold subtraction and division of literals`() {
        val tier = StackAtCompiler.compile(clause(stopLoss = op(BinOp.DIV, op(BinOp.SUB, num("10"), num("4")), num("3"))))

        assertThat(tier.slDistance).isEqualByComparingTo("2")
    }

    @Test
    fun `boolean operators are not numeric in sizing or bracket distances`() {
        assertThatThrownBy { StackAtCompiler.compile(clause(sizing = UnaryOp(UnOp.NOT, num("1")))) }
            .hasMessageContaining("STACK_AT SIZING: boolean NOT is not a numeric expression")
        assertThatThrownBy { StackAtCompiler.compile(clause(sizing = op(BinOp.AND, num("1"), num("1")))) }
            .hasMessageContaining("STACK_AT SIZING: boolean operator AND is not a numeric expression")
        assertThatThrownBy { StackAtCompiler.compile(clause(stopLoss = UnaryOp(UnOp.NOT, num("1")))) }
            .hasMessageContaining("STACK_AT BRACKET STOP LOSS BY: boolean NOT is not a numeric expression")
        assertThatThrownBy { StackAtCompiler.compile(clause(stopLoss = op(BinOp.OR, num("1"), num("1")))) }
            .hasMessageContaining("STACK_AT BRACKET STOP LOSS BY: boolean operator OR is not a numeric expression")
    }
}
