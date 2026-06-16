package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #319 — statistical primitives (zscore, regression_slope) are exposed as DSL indicators.
 *
 * Surface-level tests: the new names parse + compile through the indicator binding (single-input
 * path, including expression-fed for spreads). Per-bar math is covered by the indicator unit tests.
 */
class StatIndicatorDslTest {
    private fun parse(src: String) = Dsl.parse(src) as ParseResult.Success

    @Test
    fun `zscore of a stream field compiles`() {
        val src =
            """
            STRATEGY z VERSION 1
            SYMBOLS
              s = X:Y EVERY 1h
            RULES
              WHEN zscore(s.close, 60) > 2 THEN FLATTEN
            """.trimIndent()
        assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()
    }

    @Test
    fun `zscore of a cross-stream spread compiles`() {
        val src =
            """
            STRATEGY pairs VERSION 1
            SYMBOLS
              a = X:A EVERY 1h
              b = X:B EVERY 1h
            RULES
              WHEN zscore(a.close - 75 * b.close, 60) < 0 - 2 THEN FLATTEN
            """.trimIndent()
        assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()
    }

    @Test
    fun `regression_slope of a stream field compiles`() {
        val src =
            """
            STRATEGY trend VERSION 1
            SYMBOLS
              s = X:Y EVERY 1h
            RULES
              WHEN regression_slope(s.close, 30) > 0 THEN FLATTEN
            """.trimIndent()
        assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()
    }

    @Test
    fun `correlation of two stream closes compiles`() {
        val src =
            """
            STRATEGY pairs VERSION 1
            SYMBOLS
              a = X:A EVERY 1h
              b = X:B EVERY 1h
            RULES
              WHEN correlation(a.close, b.close, 60) > 0.8 THEN FLATTEN
            """.trimIndent()
        assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()
    }

    @Test
    fun `beta of two stream closes compiles`() {
        val src =
            """
            STRATEGY hedge VERSION 1
            SYMBOLS
              a = X:A EVERY 1h
              m = X:M EVERY 1h
            RULES
              WHEN beta(a.close, m.close, 60) > 1 THEN FLATTEN
            """.trimIndent()
        assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()
    }

    @Test
    fun `multi-regressor resid compiles`() {
        val src =
            """
            STRATEGY resid VERSION 1
            SYMBOLS
              gbp = X:GBP EVERY 1h
              eur = X:EUR EVERY 1h
              aud = X:AUD EVERY 1h
            RULES
              WHEN resid(gbp.close, eur.close, aud.close, 96) > 0 THEN FLATTEN
            """.trimIndent()
        assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()
    }

    @Test
    fun `zscore over a resid composes`() {
        val src =
            """
            STRATEGY residz VERSION 1
            SYMBOLS
              gbp = X:GBP EVERY 1h WARMUP 200 BARS
              eur = X:EUR EVERY 1h
              aud = X:AUD EVERY 1h
            RULES
              WHEN zscore(resid(gbp.close, eur.close, aud.close, 96), 96) > 2 THEN FLATTEN
            """.trimIndent()
        assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()
    }

    @Test
    fun `resid rejects a non-integer period`() {
        val src =
            """
            STRATEGY bad VERSION 1
            SYMBOLS
              gbp = X:GBP EVERY 1h
              eur = X:EUR EVERY 1h
            RULES
              WHEN resid(gbp.close, eur.close, 1.5) > 0 THEN FLATTEN
            """.trimIndent()
        assertThatThrownBy { AstCompiler().compile(parse(src).value) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `resid rejects too few series for the window`() {
        // period must exceed regressors + 1: one regressor needs period > 2.
        val src =
            """
            STRATEGY bad VERSION 1
            SYMBOLS
              gbp = X:GBP EVERY 1h
              eur = X:EUR EVERY 1h
            RULES
              WHEN resid(gbp.close, eur.close, 2) > 0 THEN FLATTEN
            """.trimIndent()
        assertThatThrownBy { AstCompiler().compile(parse(src).value) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
