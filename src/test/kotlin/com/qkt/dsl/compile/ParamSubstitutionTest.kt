package com.qkt.dsl.compile

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.parse.Parser
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ParamSubstitutionTest {
    private fun ast(src: String) =
        ((Parser(Lexer(src).tokenize()).parseFile() as ParseResult.Success).value as ParsedFile.StrategyFile).ast

    private fun refs(expr: ExprAst): List<Ref> =
        when (expr) {
            is Ref -> listOf(expr)
            is CmpOp -> refs(expr.lhs) + refs(expr.rhs)
            else -> emptyList()
        }

    @Test
    fun `a PARAM in a condition becomes its literal default`() {
        val a =
            ast(
                """
                STRATEGY s VERSION 1
                SYMBOLS gold = BYBIT:XAUUSD EVERY 5m
                PARAM threshold = 100
                RULES
                  WHEN gold.close > threshold THEN BUY gold
                """.trimIndent(),
            )
        val out = ParamSubstitution.apply(a)
        val cond = (out.rules.single() as WhenThen).cond as CmpOp
        assertThat(cond.rhs).isEqualTo(NumLit(BigDecimal(100)))
        assertThat(refs(cond)).isEmpty()
    }

    @Test
    fun `a PARAM in a SIZING action becomes its literal default`() {
        val a =
            ast(
                """
                STRATEGY s VERSION 1
                SYMBOLS gold = BYBIT:XAUUSD EVERY 5m
                PARAM riskPct = 0.01
                RULES
                  WHEN gold.close > 0 THEN BUY gold SIZING riskPct % OF EQUITY
                """.trimIndent(),
            )
        val out = ParamSubstitution.apply(a)
        val buy = (out.rules.single() as WhenThen).action as Buy
        // Non-literal percent sizing parses to `<expr> / 100` (percent convention);
        // the PARAM ref inside it must still substitute to its literal default.
        val frac = (buy.opts.sizing as SizePctEquity).frac as com.qkt.dsl.ast.BinaryOp
        assertThat(frac.lhs).isEqualTo(NumLit(BigDecimal("0.01")))
        assertThat(frac.lhs).isNotInstanceOf(Ref::class.java)
        assertThat(frac.rhs).isEqualTo(NumLit(BigDecimal(100)))
    }

    @Test
    fun `a PARAM in a DEFAULTS clause becomes its literal default`() {
        val a =
            ast(
                """
                STRATEGY s VERSION 1
                DEFAULTS { STOP_LOSS = BY stopDist }
                SYMBOLS gold = BYBIT:XAUUSD EVERY 5m
                PARAM stopDist = 50
                RULES
                  WHEN gold.close > 0 THEN BUY gold
                """.trimIndent(),
            )
        val out = ParamSubstitution.apply(a)
        val stopLoss = out.defaults!!.stopLoss as ChildBy
        assertThat(stopLoss.distance).isEqualTo(NumLit(BigDecimal(50)))
        assertThat(stopLoss.distance).isNotInstanceOf(Ref::class.java)
    }

    @Test
    fun `a strategy with no PARAMs is returned unchanged`() {
        val a =
            ast(
                """
                STRATEGY s VERSION 1
                SYMBOLS gold = BYBIT:XAUUSD EVERY 5m
                RULES
                  WHEN gold.close > 0 THEN BUY gold
                """.trimIndent(),
            )
        assertThat(ParamSubstitution.apply(a)).isSameAs(a)
    }

    @Test
    fun `an override replaces a PARAM default`() {
        val a =
            ast(
                """
                STRATEGY s VERSION 1
                SYMBOLS gold = BYBIT:XAUUSD EVERY 5m
                PARAM threshold = 100
                RULES
                  WHEN gold.close > threshold THEN BUY gold
                """.trimIndent(),
            )
        val out = ParamSubstitution.apply(a, mapOf("threshold" to "200"))
        val cond = (out.rules.single() as WhenThen).cond as CmpOp
        assertThat(cond.rhs).isEqualTo(NumLit(BigDecimal(200)))
    }

    @Test
    fun `an override replaces a LET value by name`() {
        val a =
            ast(
                """
                STRATEGY s VERSION 1
                SYMBOLS gold = BYBIT:XAUUSD EVERY 5m
                LET fast = 9
                RULES
                  WHEN gold.close > fast THEN BUY gold
                """.trimIndent(),
            )
        val out = ParamSubstitution.apply(a, mapOf("fast" to "12"))
        assertThat(out.lets.single { it.name == "fast" }.expr).isEqualTo(NumLit(BigDecimal(12)))
    }

    @Test
    fun `an unknown override name fails loudly`() {
        val a =
            ast(
                """
                STRATEGY s VERSION 1
                SYMBOLS gold = BYBIT:XAUUSD EVERY 5m
                LET fast = 9
                RULES
                  WHEN gold.close > fast THEN BUY gold
                """.trimIndent(),
            )
        assertThatThrownBy { ParamSubstitution.apply(a, mapOf("nope" to "1")) }
            .hasMessageContaining("unknown parameter 'nope'")
    }

    @Test
    fun `a non-numeric override fails loudly`() {
        val a =
            ast(
                """
                STRATEGY s VERSION 1
                SYMBOLS gold = BYBIT:XAUUSD EVERY 5m
                LET fast = 9
                RULES
                  WHEN gold.close > fast THEN BUY gold
                """.trimIndent(),
            )
        assertThatThrownBy { ParamSubstitution.apply(a, mapOf("fast" to "abc")) }
            .hasMessageContaining("must be numeric")
    }
}
