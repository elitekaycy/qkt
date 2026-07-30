package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @Test
    fun `RESIZE rejects a bracket-managed position on the same stream`() {
        val src =
            strategy(
                """
                WHEN aud.close > 0
                THEN BUY aud SIZING 1 BRACKET { STOP LOSS BY 10, TAKE PROFIT BY 20 };
                     RESIZE aud TO 0.5
                """.trimIndent(),
            )

        assertThatThrownBy { AstCompiler().compile(parse(src).value) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("RESIZE cannot target bracket-managed positions")
            .hasMessageContaining("aud")
    }

    @Test
    fun `RESIZE allows brackets on a different stream`() {
        val src =
            """
            STRATEGY t VERSION 1
            DEFAULTS { SIZING = 0.01 TIF = GTC }
            SYMBOLS
              aud = X:AUDUSD EVERY 1h
              nzd = X:NZDUSD EVERY 1h
            RULES
              WHEN aud.close > 0
              THEN BUY nzd SIZING 1 BRACKET { STOP LOSS BY 10, TAKE PROFIT BY 20 };
                   RESIZE aud TO 0.5
            """.trimIndent()

        assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()
    }
}
