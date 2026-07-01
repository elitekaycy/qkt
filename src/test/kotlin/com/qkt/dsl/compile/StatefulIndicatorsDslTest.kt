package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * The stateful capability-gap indicators compile through the DSL:
 * - anchored_return (#550), reopen_gap / gap_fill_fraction / reopen_gap_origin (#575/#542),
 * - failed_break_high/low (#598), ib_defended_high/low (#584).
 */
class StatefulIndicatorsDslTest {
    private fun parse(src: String): ParseResult.Success<com.qkt.dsl.ast.StrategyAst> {
        val r = Dsl.parse(src)
        if (r is ParseResult.Failure) error("parse failed: ${r.errors}")
        return r as ParseResult.Success
    }

    private fun ok(src: String) = assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()

    @Test
    fun `anchored_return beta-scaled cross-symbol lead compiles`() {
        ok(
            """
            STRATEGY subbar VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              gbp = BACKTEST:GBPUSD EVERY 1m,
              eur = BACKTEST:EURUSD EVERY 1m
            RULES
            WHEN beta(gbp.close, eur.close, 96) * anchored_return(gbp.candle, 30)
                 - anchored_return(eur.candle, 30) > 0.0005 AND POSITION.eur = 0
            THEN BUY eur
            """.trimIndent(),
        )
    }

    @Test
    fun `reopen gap continuation with non-fill and origin stop compiles`() {
        ok(
            """
            STRATEGY gap VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              g = BACKTEST:XAUUSD EVERY 30m
            RULES
            WHEN abs(reopen_gap(g.candle, 12)) > 2 * atr(g.candle, 14)
                 AND gap_fill_fraction(g.candle, 12) < 0.5
                 AND reopen_gap(g.candle, 12) > 0 AND POSITION.g = 0
            THEN BUY g
            WHEN POSITION.g > 0 AND g.close < reopen_gap_origin(g.candle, 12)
            THEN CLOSE g
            """.trimIndent(),
        )
    }

    @Test
    fun `failed-break straddle arming compiles`() {
        ok(
            """
            STRATEGY fakeout VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              gbp = BACKTEST:GBPUSD EVERY 15m
            RULES
            WHEN failed_break_high(gbp.candle, 20, 3, 6) > 0 AND POSITION.gbp = 0 THEN BUY gbp
            WHEN failed_break_low(gbp.candle, 20, 3, 6) > 0 AND POSITION.gbp = 0 THEN SELL gbp
            """.trimIndent(),
        )
    }

    @Test
    fun `IB late break with prior-defense confirm compiles`() {
        ok(
            """
            STRATEGY ib VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              eur = BACKTEST:EURUSD EVERY 5m,
              gbp = BACKTEST:GBPUSD EVERY 5m
            RULES
            WHEN eur.close > session_range_high(eur.candle, 8, 0, 9, 0)
                 AND ib_defended_high(eur.candle, 8, 60) > 0
                 AND gbp.close > session_range_high(gbp.candle, 8, 0, 9, 0)
                 AND POSITION.eur = 0
            THEN BUY eur
            """.trimIndent(),
        )
    }
}
