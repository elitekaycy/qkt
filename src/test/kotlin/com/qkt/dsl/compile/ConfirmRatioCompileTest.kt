package com.qkt.dsl.compile

import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `confirm_ratio` is compiled through the multi-series binding path (like `resid`), not the
 * indicator registry. This verifies the parser routes it to an indicator call and the compiler
 * binds the signal + variadic peers — including a polarity-negated peer — without error.
 */
class ConfirmRatioCompileTest {
    private fun compile(src: String) {
        val parsed = Parser(Lexer(src).tokenize()).parseStrategy()
        assertThat(parsed).isInstanceOf(ParseResult.Success::class.java)
        AstCompiler().compile((parsed as ParseResult.Success).value)
    }

    @Test
    fun `confirm_ratio with a negated peer compiles`() {
        compile(
            """
            STRATEGY x VERSION 1
            SYMBOLS
              eur = EXNESS:EURUSD EVERY 1m
              gbp = EXNESS:GBPUSD EVERY 1m
              chf = EXNESS:USDCHF EVERY 1m
            RULES
              WHEN confirm_ratio(eur.close, gbp.close, -chf.close, 4) < 0.5 THEN BUY eur SIZING 0.01
            """.trimIndent(),
        )
    }
}
