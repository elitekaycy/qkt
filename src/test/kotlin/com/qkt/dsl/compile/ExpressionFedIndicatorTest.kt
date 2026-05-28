package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #174 — indicators accept arbitrary numeric expressions as their series arg.
 *
 * Surface-level tests: parse + compile must succeed for the patterns this unlocks
 * (cross-stream spreads, log returns, normalized signals). Per-bar update math is
 * covered indirectly by the existing indicator tests; what matters here is that
 * the binding wires up without rejection.
 */
class ExpressionFedIndicatorTest {
    private fun parse(src: String) = Dsl.parse(src) as ParseResult.Success

    @Test
    fun `stddev of a cross-stream spread compiles`() {
        val src =
            """
            STRATEGY pairs VERSION 1
            SYMBOLS
              a = X:A EVERY 1h
              b = X:B EVERY 1h
            RULES
              WHEN stddev(a.close - 75 * b.close, 60) > 100 THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `stddev of a log-return expression compiles`() {
        val src =
            """
            STRATEGY logret VERSION 1
            SYMBOLS
              s = X:Y EVERY 1h
            RULES
              WHEN stddev(log(s.close) - log(s.open), 20) > 0.01 THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `sma of an arbitrary expression compiles`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN sma(g.close - g.open, 20) > 0 THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `expression with no stream reference is rejected`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN stddev(NOW.epoch_ms + 1, 5) > 0 THEN FLATTEN
            """.trimIndent()
        assertThatThrownBy { AstCompiler().compile(parse(src).value) }
            .hasMessageContaining("must reference at least one")
    }

    @Test
    fun `candle-series indicator (ATR) rejects expression series`() {
        // ATR only takes a CANDLE_SERIES; expression-fed binding is NUMERIC_SERIES only.
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN atr(g.close - g.open, 14) > 0 THEN FLATTEN
            """.trimIndent()
        assertThatThrownBy { AstCompiler().compile(parse(src).value) }
            .hasMessageContaining("CANDLE_SERIES")
    }

    @Test
    fun `existing stream-field path still works after refactor`() {
        // Regression check: classic STDDEV(close, 20) form continues to compile.
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN stddev(g.close, 20) > 1 THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `indicator chain (EMA of STDDEV) still works after refactor`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN ema(stddev(g.close, 20), 9) > 0.5 THEN FLATTEN
            """.trimIndent()
        assertThatCode {
            AstCompiler().compile(parse(src).value)
        }.doesNotThrowAnyException()
    }
}
