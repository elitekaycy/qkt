package com.qkt.dsl.parse

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserOcoEntryTest {
    private fun parsedRule(actionDsl: String): WhenThen {
        val src =
            """
            STRATEGY x VERSION 1
            SYMBOLS
                gold = EXNESS:XAUUSD EVERY 5m
            RULES
                WHEN gold.close > 0 THEN $actionDsl
            """.trimIndent()
        val r = Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Success
        return r.value.rules[0] as WhenThen
    }

    @Test
    fun `OCO_ENTRY with BUY and SELL parses as OcoEntry`() {
        val r =
            parsedRule(
                """OCO_ENTRY {
                    BUY  gold SIZING 0.20 ORDER_TYPE = STOP AT gold.close + 5,
                    SELL gold SIZING 0.20 ORDER_TYPE = STOP AT gold.close - 5
                }""",
            )
        val oco = r.action as OcoEntry
        assertThat(oco.leg1).isInstanceOf(Buy::class.java)
        assertThat(oco.leg2).isInstanceOf(Sell::class.java)
    }

    @Test
    fun `OCO_ENTRY with one leg fails parse`() {
        val src =
            """
            STRATEGY x VERSION 1
            SYMBOLS
                gold = EXNESS:XAUUSD EVERY 5m
            RULES
                WHEN gold.close > 0 THEN OCO_ENTRY { BUY gold SIZING 0.1 }
            """.trimIndent()
        val r = Parser(Lexer(src).tokenize()).parseStrategy()
        assertThat(r).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `OCO_ENTRY containing LOG fails parse`() {
        val src =
            """
            STRATEGY x VERSION 1
            SYMBOLS
                gold = EXNESS:XAUUSD EVERY 5m
            RULES
                WHEN gold.close > 0 THEN OCO_ENTRY {
                    BUY gold SIZING 0.1 ORDER_TYPE = STOP AT gold.close + 5,
                    LOG "fired"
                }
            """.trimIndent()
        val r = Parser(Lexer(src).tokenize()).parseStrategy()
        assertThat(r).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `OCO_ENTRY preserves per-leg BRACKET`() {
        val r =
            parsedRule(
                """OCO_ENTRY {
                    BUY  gold SIZING 0.20 ORDER_TYPE = STOP AT gold.close + 5
                         BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 },
                    SELL gold SIZING 0.20 ORDER_TYPE = STOP AT gold.close - 5
                         BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 }
                }""",
            )
        val oco = r.action as OcoEntry
        assertThat((oco.leg1 as Buy).opts.bracket).isNotNull
        assertThat((oco.leg2 as Sell).opts.bracket).isNotNull
    }
}
