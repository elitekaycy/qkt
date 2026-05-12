package com.qkt.dsl.parse

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserStackAtTest {
    private fun parsedAction(actionDsl: String): Buy {
        val src =
            """
            STRATEGY x VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 0 THEN $actionDsl
            """.trimIndent()
        val r = Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Success
        return (r.value.rules[0] as WhenThen).action as Buy
    }

    @Test
    fun `single STACK_AT clause parses with threshold duration sizing bracket`() {
        val buy =
            parsedAction(
                """BUY btc SIZING 0.1
                STACK_AT MFE >= 50 WITHIN 30m
                    SIZING 0.05
                    BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }""",
            )
        val stacks = buy.opts.stackAts
        assertThat(stacks).hasSize(1)
        val clause = stacks[0]
        assertThat(clause.mfeThreshold).isInstanceOf(NumLit::class.java)
        assertThat((clause.mfeThreshold as NumLit).value).isEqualByComparingTo("50")
        assertThat(clause.withinDuration.millis).isEqualTo(30L * 60 * 1000) // 30 min
        assertThat(clause.sizing).isInstanceOf(SizeQty::class.java)
        assertThat(((clause.sizing as SizeQty).expr as NumLit).value).isEqualByComparingTo("0.05")
        assertThat(clause.bracket.stopLoss).isNotNull
        assertThat(clause.bracket.takeProfit).isNotNull
    }

    @Test
    fun `multiple STACK_AT clauses on one action all preserved`() {
        val buy =
            parsedAction(
                """BUY btc SIZING 0.1
                STACK_AT MFE >= 10 WITHIN 30m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
                STACK_AT MFE >= 20 WITHIN 60m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
                STACK_AT MFE >= 30 WITHIN 90m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }""",
            )
        val stacks = buy.opts.stackAts
        assertThat(stacks).hasSize(3)
        assertThat((stacks[0].mfeThreshold as NumLit).value).isEqualByComparingTo("10")
        assertThat((stacks[1].mfeThreshold as NumLit).value).isEqualByComparingTo("20")
        assertThat((stacks[2].mfeThreshold as NumLit).value).isEqualByComparingTo("30")
        assertThat(stacks[0].withinDuration.millis).isEqualTo(30L * 60 * 1000)
        assertThat(stacks[1].withinDuration.millis).isEqualTo(60L * 60 * 1000)
        assertThat(stacks[2].withinDuration.millis).isEqualTo(90L * 60 * 1000)
    }

    @Test
    fun `STACK_AT supports a computed threshold expression`() {
        val buy =
            parsedAction(
                """BUY btc SIZING 0.1
                STACK_AT MFE >= 10 * 5 WITHIN 1h SIZING 0.05 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }""",
            )
        val stacks = buy.opts.stackAts
        assertThat(stacks).hasSize(1)
        // 10 * 5 stays as a BinaryOp until eval time; just verify it parsed to something other than a bare NumLit
        assertThat(stacks[0].mfeThreshold).isInstanceOf(com.qkt.dsl.ast.BinaryOp::class.java)
    }

    @Test
    fun `STACK_AT missing MFE keyword fails parse`() {
        val src =
            """
            STRATEGY x VERSION 1
            SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 0 THEN BUY btc SIZING 0.1
                    STACK_AT >= 10 WITHIN 30m SIZING 0.05 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
            """.trimIndent()
        val r = Parser(Lexer(src).tokenize()).parseStrategy()
        assertThat(r).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `STACK_AT missing WITHIN fails parse`() {
        val src =
            """
            STRATEGY x VERSION 1
            SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 0 THEN BUY btc SIZING 0.1
                    STACK_AT MFE >= 10 SIZING 0.05 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
            """.trimIndent()
        val r = Parser(Lexer(src).tokenize()).parseStrategy()
        assertThat(r).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `BUY without any STACK_AT keeps stackAts empty`() {
        val buy = parsedAction("BUY btc SIZING 0.1 BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 100 }")
        assertThat(buy.opts.stackAts).isEmpty()
    }
}
