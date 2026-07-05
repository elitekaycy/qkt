package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * The two new capability-gap indicators compile through the DSL:
 * - RUNLENGTH (#589): a signed same-direction streak on the close (or any expression).
 * - SEASONAL_RANGE_STDEV (#581): the per-UTC-hour range dispersion that, with SEASONAL_RANGE,
 *   forms an hour-relative z-score of bar range.
 */
class RunLengthSeasonalStdevDslTest {
    private fun parse(src: String): ParseResult.Success<com.qkt.dsl.ast.StrategyAst> {
        val r = Dsl.parse(src)
        if (r is ParseResult.Failure) error("parse failed: ${r.errors}")
        return r as ParseResult.Success
    }

    private fun ok(src: String) = assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()

    @Test
    fun `runlength gates on a same-direction close streak`() {
        ok(
            """
            STRATEGY streak VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              eur = BACKTEST:EURUSD EVERY 1d
            RULES
              WHEN runlength(eur.close) >= 4 AND runlength(eur.close) <= 8 AND POSITION.eur = 0
              THEN BUY eur
            """.trimIndent(),
        )
    }

    @Test
    fun `seasonal range stdev forms an hour-relative z-score of bar range`() {
        ok(
            """
            STRATEGY hourz VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              g = BACKTEST:XAUUSD EVERY 1h
            LET rng = g.high - g.low,
                hourz = (rng - seasonal_range(g.candle, 20)) / seasonal_range_stdev(g.candle, 20)
            RULES
              WHEN hourz > 2.5 AND POSITION.g = 0 THEN BUY g
            """.trimIndent(),
        )
    }
}
