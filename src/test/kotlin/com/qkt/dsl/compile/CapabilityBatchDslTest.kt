package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * End-to-end parse + compile for the capability-gap batch primitives, asserting the binding
 * wires for each one. Numeric-series indicators take an expression (`s.close`, arithmetic);
 * candle-fed indicators take the `s.candle` series argument.
 */
class CapabilityBatchDslTest {
    private fun parse(src: String) = Dsl.parse(src) as ParseResult.Success

    private fun strategy(rule: String): String =
        """
        STRATEGY t VERSION 1
        SYMBOLS
          s = X:Y EVERY 1h
        RULES
          $rule
        """.trimIndent()

    private fun compiles(rule: String) =
        assertThatCode {
            AstCompiler().compile(parse(strategy(rule)).value)
        }.doesNotThrowAnyException()

    @Test
    fun `lag and the bracket bar-offset sugar compile`() {
        compiles("WHEN lag(s.close, 21) - lag(s.close, 252) > 0 THEN FLATTEN")
        compiles("WHEN s.close[20] > s.close[0] THEN FLATTEN")
    }

    @Test
    fun `variance_ratio compiles on a series and on a ratio expression`() {
        compiles("WHEN variance_ratio(s.close, 5, 100) < 1 THEN FLATTEN")
        compiles("WHEN variance_ratio(s.close / s.open, 5, 100) > 1 THEN FLATTEN")
    }

    @Test
    fun `round_to compiles`() {
        compiles("WHEN s.close - round_to(s.close, 25) > 0 THEN FLATTEN")
    }

    @Test
    fun `NOW days_in_month turn-of-month gate compiles`() {
        compiles("WHEN NOW.day >= NOW.days_in_month - 1 THEN FLATTEN")
    }

    @Test
    fun `floor-trader pivots compile on the candle series`() {
        compiles("WHEN s.close > pivot_p(s.candle) THEN FLATTEN")
        compiles("WHEN s.close > pivot_r1(s.candle) THEN FLATTEN")
        compiles("WHEN s.close < pivot_s1(s.candle) THEN FLATTEN")
    }

    @Test
    fun `seasonal_range compiles on the candle series`() {
        compiles("WHEN s.high - s.low > seasonal_range(s.candle, 20) THEN FLATTEN")
    }

    @Test
    fun `session_momentum compiles on the candle series`() {
        compiles("WHEN session_momentum(s.candle, 12, 14, 3) > 0 THEN FLATTEN")
    }
}
