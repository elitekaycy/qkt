package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/** Parse + compile coverage for the `RESIZE` action across its sizing-target forms. */
class ResizeDslTest {
    private fun parse(src: String) = Dsl.parse(src) as ParseResult.Success

    private fun strategy(rule: String): String =
        """
        STRATEGY t VERSION 1
        DEFAULTS { SIZING = 0.01 TIF = GTC }
        SYMBOLS
          aud = X:Y EVERY 1h
        RULES
          $rule
        """.trimIndent()

    private fun compiles(rule: String) =
        assertThatCode {
            AstCompiler().compile(parse(strategy(rule)).value)
        }.doesNotThrowAnyException()

    @Test
    fun `RESIZE to an inverse-vol target compiles`() {
        compiles("WHEN aud.close > 0 THEN RESIZE aud TO 0.01 / atr(aud.candle, 14)")
    }

    @Test
    fun `RESIZE with a MIN_STEP deadband compiles`() {
        compiles("WHEN aud.close > 0 THEN RESIZE aud TO 0.5 MIN_STEP 0.01")
    }

    @Test
    fun `RESIZE to a percent-of-equity target compiles`() {
        compiles("WHEN aud.close > 0 THEN RESIZE aud TO 5 PCT OF EQUITY")
    }

    @Test
    fun `RESIZE to zero compiles`() {
        compiles("WHEN aud.close > 0 THEN RESIZE aud TO 0")
    }
}
