package com.qkt.dsl.parse

import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserResizeTest {
    private fun resize(rule: String): Resize {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              aud = X:Y EVERY 1h
            RULES
              $rule
            """.trimIndent()
        val res = Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Success
        return (res.value.rules[0] as WhenThen).action as Resize
    }

    @Test
    fun `parses RESIZE TO a quantity target`() {
        val a = resize("WHEN aud.close > 0 THEN RESIZE aud TO 0.5")
        assertThat(a.stream).isEqualTo("aud")
        assertThat(a.target).isInstanceOf(SizeQty::class.java)
        assertThat(a.minStep).isNull()
    }

    @Test
    fun `parses an indicator target with a MIN_STEP deadband`() {
        val a = resize("WHEN aud.close > 0 THEN RESIZE aud TO 0.01 / atr(aud.candle, 14) MIN_STEP 0.001")
        assertThat(a.target).isInstanceOf(SizeQty::class.java)
        assertThat(a.minStep).isNotNull()
    }

    @Test
    fun `parses a percent-of-equity target`() {
        val a = resize("WHEN aud.close > 0 THEN RESIZE aud TO 5 PCT OF EQUITY")
        assertThat(a.target).isInstanceOf(SizePctEquity::class.java)
    }
}
