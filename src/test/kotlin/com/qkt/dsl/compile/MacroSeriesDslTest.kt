package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

class MacroSeriesDslTest {
    private fun compile(src: String) = AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)

    @Test
    fun `a MACRO stream value reads (alias of the candle close)`() {
        val src =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold    = BACKTEST:XAUUSD EVERY 5m
                real10y = MACRO:DFII10   EVERY 1d
            RULES
                WHEN gold.close > real10y.value AND POSITION.gold = 0
                THEN BUY gold SIZING 0.01
            """.trimIndent()
        // Compiles without error: reading a MACRO series via .value is legal.
        compile(src)
    }

    @Test
    fun `ordering a MACRO stream is a compile error`() {
        val src =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold    = BACKTEST:XAUUSD EVERY 5m
                real10y = MACRO:DFII10   EVERY 1d
            RULES
                WHEN real10y.value < 2
                THEN BUY real10y SIZING 0.01
            """.trimIndent()
        val ex = catchThrowable { compile(src) }
        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex.message).contains("read-only").contains("real10y")
    }

    @Test
    fun `closing a MACRO stream is a compile error`() {
        val src =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold    = BACKTEST:XAUUSD EVERY 5m
                real10y = MACRO:DFII10   EVERY 1d
            RULES
                WHEN real10y.value < 2
                THEN CLOSE real10y
            """.trimIndent()
        val ex = catchThrowable { compile(src) }
        assertThat(ex.message).contains("read-only")
    }
}
