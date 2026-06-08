package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
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
}
