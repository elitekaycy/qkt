package com.qkt.dsl.parse

import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.Stop
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

    @Test
    fun `entry inside LIMIT AT becomes StackEntryRef and populates layer at`() {
        val rule =
            parseRule(
                "WHEN btc.close > 100 THEN BUY btc STACK [ 0.1, 0.2 LIMIT AT entry + 100 ]",
            )
        val stack = (rule.action as Buy).opts.stack as com.qkt.dsl.ast.StackLayers
        val layer2 = stack.layers[1]
        assertThat(layer2.orderType).isInstanceOf(com.qkt.dsl.ast.Limit::class.java)
        // Both orderType.price and layer.at must reference StackEntryRef.
        val limitPrice = (layer2.orderType as com.qkt.dsl.ast.Limit).price as com.qkt.dsl.ast.BinaryOp
        assertThat(limitPrice.lhs).isEqualTo(com.qkt.dsl.ast.StackEntryRef)
        val layerAt = layer2.at as com.qkt.dsl.ast.BinaryOp
        assertThat(layerAt.lhs).isEqualTo(com.qkt.dsl.ast.StackEntryRef)
    }

    @Test
    fun `LIMIT layer with explicit AT clause is parse error`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 100 THEN BUY btc STACK [ 0.1, 0.2 LIMIT AT entry + 100 AT entry + 200 ]
            """.trimIndent()
        val tokens = Lexer(src).tokenize()
        val result = Parser(tokens).parseStrategy()
        assertThat(result).isInstanceOf(ParseResult.Failure::class.java)
        val failure = result as ParseResult.Failure
        assertThat(failure.errors.any { it.message.contains("LIMIT") }).isTrue()
    }

    @Test
    fun `STOP layer at entry+100 populates layer at`() {
        val rule =
            parseRule(
                "WHEN btc.close > 100 THEN BUY btc STACK [ 0.1, 0.2 STOP AT entry + 100 ]",
            )
        val stack = (rule.action as Buy).opts.stack as com.qkt.dsl.ast.StackLayers
        val layer2 = stack.layers[1]
        assertThat(layer2.orderType).isInstanceOf(com.qkt.dsl.ast.Stop::class.java)
        val layerAt = layer2.at as com.qkt.dsl.ast.BinaryOp
        assertThat(layerAt.lhs).isEqualTo(com.qkt.dsl.ast.StackEntryRef)
    }

    private fun parseFailure(src: String): ParseResult.Failure<*> {
        val full =
            """
            STRATEGY t VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                $src
            """.trimIndent()
        val tokens = Lexer(full).tokenize()
        val result = Parser(tokens).parseStrategy()
        assertThat(result).isInstanceOf(ParseResult.Failure::class.java)
        return result as ParseResult.Failure<*>
    }

    @Test
    fun `WITHIN attaches to SPACING form`() {
        val rule = parseRule("WHEN btc.close > 100 THEN BUY btc SIZING 0.1 STACK 3 SPACING 100 WITHIN 1h")
        val stack = (rule.action as Buy).opts.stack as StackSpacing
        assertThat(stack.within?.millis).isEqualTo(3_600_000L)
    }

    @Test
    fun `WITHIN attaches to layer-list form`() {
        val rule = parseRule("WHEN btc.close > 100 THEN BUY btc STACK [ 0.1, 0.2 AT entry + 100 ] WITHIN 30m")
        val stack = (rule.action as Buy).opts.stack as StackLayers
        assertThat(stack.within?.millis).isEqualTo(1_800_000L)
    }

    @Test
    fun `outer SIZING with layer-list sizing is parse error`() {
        val failure = parseFailure("WHEN btc.close > 100 THEN BUY btc SIZING 0.1 STACK [ 0.1, 0.2 AT entry + 100 ]")
        assertThat(failure.errors.any { it.message.contains("outer SIZING") }).isTrue()
    }

    @Test
    fun `STACK count zero is parse error`() {
        val failure = parseFailure("WHEN btc.close > 100 THEN BUY btc SIZING 0.1 STACK 0 SPACING 100")
        assertThat(failure.errors.any { it.message.contains("STACK count") }).isTrue()
    }

    @Test
    fun `empty layer list is parse error`() {
        val failure = parseFailure("WHEN btc.close > 100 THEN BUY btc STACK [ ]")
        assertThat(failure.errors.any { it.message.contains("layer list") }).isTrue()
    }

    @Test
    fun `layer two without AT is parse error`() {
        val failure = parseFailure("WHEN btc.close > 100 THEN BUY btc STACK [ 0.1, 0.2 ]")
        assertThat(failure.errors.any { it.message.contains("AT") }).isTrue()
    }
}
