package com.qkt.dsl.parse

import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.WhenThen
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserStackTest {
    private fun parseRule(src: String): WhenThen {
        val full =
            """
            STRATEGY t VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                $src
            """.trimIndent()
        val tokens = Lexer(full).tokenize()
        val strat = (Parser(tokens).parseStrategy() as ParseResult.Success).value
        return strat.rules.single() as WhenThen
    }

    @Test
    fun `STACK count SPACING parses with default direction`() {
        val rule = parseRule("WHEN btc.close > 100 THEN BUY btc SIZING 0.1 STACK 3 SPACING 100")
        val buy = rule.action as Buy
        val stack = buy.opts.stack as StackSpacing
        assertThat(stack.count).isEqualTo(3)
        assertThat(stack.spacing).isEqualTo(NumLit(BigDecimal("100")))
        assertThat(stack.direction).isEqualTo(StackDirection.TRADE_DIRECTION)
        assertThat(stack.within).isNull()
    }

    @Test
    fun `STACK 3 SPACING 100 ABOVE parses with ABOVE direction`() {
        val rule = parseRule("WHEN btc.close > 100 THEN BUY btc SIZING 0.1 STACK 3 SPACING 100 ABOVE")
        val stack = (rule.action as Buy).opts.stack as StackSpacing
        assertThat(stack.direction).isEqualTo(StackDirection.ABOVE)
    }

    @Test
    fun `STACK 3 SPACING 100 BELOW parses with BELOW direction`() {
        val rule = parseRule("WHEN btc.close > 100 THEN BUY btc SIZING 0.1 STACK 3 SPACING 100 BELOW")
        val stack = (rule.action as Buy).opts.stack as StackSpacing
        assertThat(stack.direction).isEqualTo(StackDirection.BELOW)
    }

    @Test
    fun `layer-list form parses with three layers`() {
        val rule =
            parseRule(
                "WHEN btc.close > 100 THEN BUY btc " +
                    "STACK [ 0.1, 0.2 AT entry + 100, 0.3 LIMIT AT entry + 200 ]",
            )
        val stack = (rule.action as Buy).opts.stack as StackLayers
        assertThat(stack.layers).hasSize(3)
        assertThat(stack.layers[0].at).isNull()
        assertThat(stack.layers[0].orderType).isNull()
        assertThat(stack.layers[1].at).isNotNull
        assertThat(stack.layers[2].orderType).isInstanceOf(Limit::class.java)
    }

    @Test
    fun `entry inside layer AT becomes StackEntryRef`() {
        val rule =
            parseRule(
                "WHEN btc.close > 100 THEN BUY btc STACK [ 0.1, 0.2 AT entry + 100 ]",
            )
        val stack = (rule.action as Buy).opts.stack as StackLayers
        val expr = stack.layers[1].at as BinaryOp
        assertThat(expr.lhs).isEqualTo(StackEntryRef)
    }

    @Test
    fun `trailing comma in layer-list is allowed`() {
        val rule =
            parseRule(
                "WHEN btc.close > 100 THEN BUY btc STACK [ 0.1, 0.2 AT entry + 100, ]",
            )
        val stack = (rule.action as Buy).opts.stack as StackLayers
        assertThat(stack.layers).hasSize(2)
    }
}
