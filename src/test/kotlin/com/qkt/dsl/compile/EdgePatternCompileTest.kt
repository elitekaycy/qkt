package com.qkt.dsl.compile

import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proves that three edge patterns qkt-forge believed were inexpressible already compile in the
 * current DSL — they were authoring-grammar gaps, not engine gaps. Each test compiles a faithful
 * rendering of the motivating thesis end-to-end (parse + bind).
 */
class EdgePatternCompileTest {
    private fun compile(src: String) {
        val parsed = Parser(Lexer(src).tokenize()).parseStrategy()
        val errors =
            (parsed as? ParseResult.Failure)?.errors?.joinToString(
                "\n",
            ) { "${it.line}:${it.col} ${it.message}" }
        assertThat(parsed).`as`("parse errors:\n%s", errors).isInstanceOf(ParseResult.Success::class.java)
        AstCompiler().compile((parsed as ParseResult.Success).value)
    }

    @Test
    fun `top-of-hour gate via NOW MINUTE_UTC compiles`() {
        // Enter in the first minute of every hour, force-flat by minute 4.
        compile(
            """
            STRATEGY tophour VERSION 1
            SYMBOLS
              eur = EXNESS:EURUSD EVERY 1m
            RULES
              WHEN NOW.MINUTE_UTC = 0 AND eur.close > eur.open THEN BUY eur SIZING 0.01
              WHEN NOW.MINUTE_UTC >= 4 THEN SELL eur SIZING 0.01
            """.trimIndent(),
        )
    }

    @Test
    fun `nr7 and inside-bar via highest lowest over a range expression compile`() {
        // NR7: today's range narrower than the prior six. Inside bar: today inside yesterday.
        // Yesterday's high/low come from HIGHEST/LOWEST with period 1 (the built-in one-bar lag).
        compile(
            """
            STRATEGY nr7 VERSION 1
            SYMBOLS
              gbp = EXNESS:GBPUSD EVERY 1d
              eur = EXNESS:EURUSD EVERY 1d
            RULES
              WHEN (gbp.high - gbp.low) < LOWEST(gbp.high - gbp.low, 6)
                AND gbp.high < HIGHEST(gbp.high, 1)
                AND gbp.low > LOWEST(gbp.low, 1)
                AND eur.close > eur.open
                THEN BUY gbp SIZING 0.01
            """.trimIndent(),
        )
    }

    @Test
    fun `rejection wick and round-number grid distance compile`() {
        // Grid: distance past the nearest $10 figure via MOD. Wick: upper shadow = high - max(open,close);
        // since MAX is a windowed aggregate keyword, the scalar body-extreme is a CASE expression.
        compile(
            """
            STRATEGY wick VERSION 1
            SYMBOLS
              gold = EXNESS:XAUUSD EVERY 5m
            RULES
              WHEN MOD(gold.close, 10) < 1
                AND gold.high - (CASE WHEN gold.open > gold.close THEN gold.open ELSE gold.close END) > 2
                THEN SELL gold SIZING 0.01
            """.trimIndent(),
        )
    }
}
