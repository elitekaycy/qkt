package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * Proves the existing DSL already expresses a cross-symbol "k-of-N peers bursting in the same
 * bar" gate, without any dedicated breadth indicator. The k-of-N set is an OR of the AND-pairs,
 * and a single rule with that one boolean condition arms exactly one order on the false->true
 * edge — so it does not over-arm the way N separate rules would.
 *
 * Covers the two breadth shapes qkt-forge asked for:
 * - realized range over an ATR multiple (the #520 thesis), and
 * - a realized-range volatility z-burst (the #524 thesis).
 */
class BreadthGateCompileTest {
    private fun parse(src: String): ParseResult.Success<com.qkt.dsl.ast.StrategyAst> {
        val r = Dsl.parse(src)
        if (r is ParseResult.Failure) error("parse failed: ${r.errors}")
        return r as ParseResult.Success
    }

    @Test
    fun `2-of-3 range-over-ATR breadth gate compiles and arms one order`() {
        val src =
            """
            STRATEGY breadth_range VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              eur = BACKTEST:EURUSD EVERY 30m,
              gbp = BACKTEST:GBPUSD EVERY 30m,
              aud = BACKTEST:AUDUSD EVERY 30m,
              nzd = BACKTEST:NZDUSD EVERY 30m
            LET eb = (eur.high - eur.low) > 1.5 * atr(eur.candle, 14),
                gb = (gbp.high - gbp.low) > 1.5 * atr(gbp.candle, 14),
                ab = (aud.high - aud.low) > 1.5 * atr(aud.candle, 14),
                nb = (nzd.high - nzd.low) > 1.5 * atr(nzd.candle, 14)
            RULES
              WHEN eb AND ( (gb AND ab) OR (gb AND nb) OR (ab AND nb) ) AND POSITION.eur = 0
              THEN BUY eur
            """.trimIndent()
        assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()
    }

    @Test
    fun `2-of-3 volatility z-burst breadth gate compiles and arms one order`() {
        val src =
            """
            STRATEGY breadth_zburst VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              gbp = BACKTEST:GBPUSD EVERY 30m,
              eur = BACKTEST:EURUSD EVERY 30m,
              aud = BACKTEST:AUDUSD EVERY 30m,
              nzd = BACKTEST:NZDUSD EVERY 30m
            LET gz = zscore(gbp.high - gbp.low, 96) > 2,
                ez = zscore(eur.high - eur.low, 96) > 2,
                az = zscore(aud.high - aud.low, 96) > 2,
                nz = zscore(nzd.high - nzd.low, 96) > 2
            RULES
              WHEN gz AND ( (ez AND az) OR (ez AND nz) OR (az AND nz) ) AND POSITION.gbp = 0
              THEN BUY gbp
            """.trimIndent()
        assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()
    }
}
