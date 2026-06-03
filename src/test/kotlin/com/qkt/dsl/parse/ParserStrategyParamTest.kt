package com.qkt.dsl.parse

import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StringLit
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserStrategyParamTest {
    private fun parse(src: String) =
        (Parser(Lexer(src).tokenize()).parseFile() as ParseResult.Success).value as ParsedFile.StrategyFile

    @Test
    fun `parses PARAM declarations of each scalar type`() {
        val s =
            parse(
                """
                STRATEGY s VERSION 1
                SYMBOLS gold = BYBIT:XAUUSD EVERY 5m
                PARAM riskPct = 0.01
                PARAM enabled = TRUE
                PARAM mode = "fast"
                RULES
                  WHEN gold.close > 0 THEN LOG "x"
                """.trimIndent(),
            ).ast
        assertThat(s.params.map { it.name }).containsExactly("riskPct", "enabled", "mode")
        assertThat(s.params[0].value).isEqualTo(NumLit(BigDecimal("0.01")))
        assertThat(s.params[1].value).isEqualTo(BoolLit(true))
        assertThat(s.params[2].value).isEqualTo(StringLit("fast"))
    }

    @Test
    fun `rejects a non-literal PARAM default`() {
        val r =
            Parser(
                Lexer(
                    "STRATEGY s VERSION 1\nSYMBOLS gold = BYBIT:XAUUSD EVERY 5m\nPARAM x = ema(gold.close, 9)\nRULES\nWHEN gold.close > 0 THEN LOG \"x\"",
                ).tokenize(),
            ).parseFile()
        assertThat(r).isInstanceOf(ParseResult.Failure::class.java)
    }
}
