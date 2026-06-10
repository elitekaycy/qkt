package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerOperatorsTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx =
        EvalContext(
            candle = candle,
            streams = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `addition`() {
        val v =
            ExprCompiler()
                .compile(BinaryOp(BinOp.ADD, NumLit(BigDecimal("2")), NumLit(BigDecimal("3"))))
                .evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("5")
    }

    @Test
    fun `subtraction`() {
        val v =
            ExprCompiler()
                .compile(BinaryOp(BinOp.SUB, NumLit(BigDecimal("5")), NumLit(BigDecimal("2"))))
                .evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("3")
    }

    @Test
    fun `multiplication`() {
        val v =
            ExprCompiler()
                .compile(BinaryOp(BinOp.MUL, NumLit(BigDecimal("4")), NumLit(BigDecimal("3"))))
                .evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("12")
    }

    @Test
    fun `division uses Money context`() {
        val v =
            ExprCompiler()
                .compile(BinaryOp(BinOp.DIV, NumLit(BigDecimal("10")), NumLit(BigDecimal("3"))))
                .evaluate(ctx) as Value.Num
        assertThat(v.v.precision()).isLessThanOrEqualTo(Money.CONTEXT.precision)
    }

    @Test
    fun `comparison greater-than`() {
        val v =
            ExprCompiler()
                .compile(CmpOp(Cmp.GT, NumLit(BigDecimal("5")), NumLit(BigDecimal("3"))))
                .evaluate(ctx) as Value.Bool
        assertThat(v.v).isTrue()
    }

    @Test
    fun `boolean and`() {
        val v =
            ExprCompiler()
                .compile(BinaryOp(BinOp.AND, BoolLit(true), BoolLit(false)))
                .evaluate(ctx) as Value.Bool
        assertThat(v.v).isFalse()
    }

    @Test
    fun `unary not`() {
        val v =
            ExprCompiler()
                .compile(UnaryOp(UnOp.NOT, BoolLit(false)))
                .evaluate(ctx) as Value.Bool
        assertThat(v.v).isTrue()
    }

    @Test
    fun `unary neg`() {
        val v =
            ExprCompiler()
                .compile(UnaryOp(UnOp.NEG, NumLit(BigDecimal("2"))))
                .evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("-2")
    }

    @Test
    fun `TRUE OR undefined short-circuits to TRUE`() {
        val orExpr = ExprCompiler().compile(BinaryOp(BinOp.OR, BoolLit(true), undefinedAst()))
        val v = orExpr.evaluate(ctx)
        assertThat(v).isEqualTo(Value.Bool(true))
        // Symmetric: undefined OR TRUE.
        val orExpr2 = ExprCompiler().compile(BinaryOp(BinOp.OR, undefinedAst(), BoolLit(true)))
        assertThat(orExpr2.evaluate(ctx)).isEqualTo(Value.Bool(true))
        // FALSE OR undefined stays undefined — the unknown side could decide it.
        val orExpr3 = ExprCompiler().compile(BinaryOp(BinOp.OR, BoolLit(false), undefinedAst()))
        assertThat(orExpr3.evaluate(ctx)).isEqualTo(Value.Undefined)
    }

    @Test
    fun `FALSE AND undefined short-circuits to FALSE`() {
        val andExpr = ExprCompiler().compile(BinaryOp(BinOp.AND, BoolLit(false), undefinedAst()))
        assertThat(andExpr.evaluate(ctx)).isEqualTo(Value.Bool(false))
        val andExpr2 = ExprCompiler().compile(BinaryOp(BinOp.AND, undefinedAst(), BoolLit(false)))
        assertThat(andExpr2.evaluate(ctx)).isEqualTo(Value.Bool(false))
        // TRUE AND undefined stays undefined.
        val andExpr3 = ExprCompiler().compile(BinaryOp(BinOp.AND, BoolLit(true), undefinedAst()))
        assertThat(andExpr3.evaluate(ctx)).isEqualTo(Value.Undefined)
    }

    /** A boolean-shaped expression that evaluates Undefined: comparing 1 to an unwarm value. */
    private fun undefinedAst(): com.qkt.dsl.ast.ExprAst =
        CmpOp(
            Cmp.GT,
            NumLit(BigDecimal.ONE),
            com.qkt.dsl.ast
                .IsNull(NumLit(BigDecimal.ONE), negated = false),
        )
}
