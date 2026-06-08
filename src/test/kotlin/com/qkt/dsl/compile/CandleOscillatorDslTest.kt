package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * #320 — candle-fed oscillators (Williams %R, CCI, Stochastic, OBV) are exposed as DSL indicators
 * via the `stream.candle` series argument. Parse + compile must wire the CANDLE_SERIES binding.
 */
class CandleOscillatorDslTest {
    private fun parse(src: String) = Dsl.parse(src) as ParseResult.Success

    private fun strategy(rule: String): String =
        """
        STRATEGY t VERSION 1
        SYMBOLS
          s = X:Y EVERY 1h
        RULES
          $rule
        """.trimIndent()

    @Test
    fun `williams_r compiles`() {
        assertThatCode {
            AstCompiler().compile(parse(strategy("WHEN williams_r(s.candle, 14) < 0 - 80 THEN FLATTEN")).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `cci compiles`() {
        assertThatCode {
            AstCompiler().compile(parse(strategy("WHEN cci(s.candle, 20) > 100 THEN FLATTEN")).value)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `stochastic k and d compile`() {
        assertThatCode {
            AstCompiler().compile(
                parse(strategy("WHEN stoch_k(s.candle, 14, 3) > stoch_d(s.candle, 14, 3) THEN FLATTEN")).value,
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `obv compiles`() {
        assertThatCode {
            AstCompiler().compile(parse(strategy("WHEN obv(s.candle) > 0 THEN FLATTEN")).value)
        }.doesNotThrowAnyException()
    }
}
