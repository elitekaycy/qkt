package com.qkt.dsl.compile

import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.NumLit
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerBetweenInCaseTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx =
        EvalContext(
            candle = candle,
            streamSymbols = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `BETWEEN includes endpoints`() {
        val ec = ExprCompiler()
        assertThat(
            (
                ec
                    .compile(Between(NumLit(BigDecimal("5")), NumLit(BigDecimal("1")), NumLit(BigDecimal("10"))))
                    .evaluate(ctx) as Value.Bool
            ).v,
        ).isTrue()
        assertThat(
            (
                ec
                    .compile(Between(NumLit(BigDecimal("1")), NumLit(BigDecimal("1")), NumLit(BigDecimal("10"))))
                    .evaluate(ctx) as Value.Bool
            ).v,
        ).isTrue()
        assertThat(
            (
                ec
                    .compile(Between(NumLit(BigDecimal("10")), NumLit(BigDecimal("1")), NumLit(BigDecimal("10"))))
                    .evaluate(ctx) as Value.Bool
            ).v,
        ).isTrue()
    }

    @Test
    fun `BETWEEN excludes outside`() {
        val ec = ExprCompiler()
        assertThat(
            (
                ec
                    .compile(Between(NumLit(BigDecimal("0")), NumLit(BigDecimal("1")), NumLit(BigDecimal("10"))))
                    .evaluate(ctx) as Value.Bool
            ).v,
        ).isFalse()
        assertThat(
            (
                ec
                    .compile(Between(NumLit(BigDecimal("11")), NumLit(BigDecimal("1")), NumLit(BigDecimal("10"))))
                    .evaluate(ctx) as Value.Bool
            ).v,
        ).isFalse()
    }

    @Test
    fun `IN-list matches numeric members`() {
        val expr =
            InList(
                NumLit(BigDecimal("3")),
                listOf(NumLit(BigDecimal("1")), NumLit(BigDecimal("3")), NumLit(BigDecimal("5"))),
            )
        assertThat((ExprCompiler().compile(expr).evaluate(ctx) as Value.Bool).v).isTrue()
    }

    @Test
    fun `IN-list misses non-members`() {
        val expr =
            InList(
                NumLit(BigDecimal("4")),
                listOf(NumLit(BigDecimal("1")), NumLit(BigDecimal("3")), NumLit(BigDecimal("5"))),
            )
        assertThat((ExprCompiler().compile(expr).evaluate(ctx) as Value.Bool).v).isFalse()
    }

    @Test
    fun `IN-list empty members is always false`() {
        val expr = InList(NumLit(BigDecimal("1")), emptyList())
        assertThat((ExprCompiler().compile(expr).evaluate(ctx) as Value.Bool).v).isFalse()
    }

    @Test
    fun `CASE WHEN picks first matching branch`() {
        val expr =
            CaseWhen(
                branches =
                    listOf(
                        BoolLit(false) to NumLit(BigDecimal("1")),
                        BoolLit(true) to NumLit(BigDecimal("2")),
                        BoolLit(true) to NumLit(BigDecimal("3")),
                    ),
                elseExpr = NumLit(BigDecimal("0")),
            )
        assertThat((ExprCompiler().compile(expr).evaluate(ctx) as Value.Num).v).isEqualByComparingTo("2")
    }

    @Test
    fun `CASE WHEN falls through to ELSE when no branch matches`() {
        val expr =
            CaseWhen(
                branches = listOf(BoolLit(false) to NumLit(BigDecimal("1"))),
                elseExpr = NumLit(BigDecimal("99")),
            )
        assertThat((ExprCompiler().compile(expr).evaluate(ctx) as Value.Num).v).isEqualByComparingTo("99")
    }
}
