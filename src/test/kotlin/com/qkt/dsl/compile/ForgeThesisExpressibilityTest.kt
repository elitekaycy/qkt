package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * Proves that a set of quant theses qkt-forge filed as "capability gaps" are already
 * expressible in today's DSL by composing existing primitives — no new indicator needed.
 * Each test compiles the representative construct so a regression that breaks the
 * composition (e.g. expression-fed indicators, CASE, the NOW day accessors) is caught here.
 *
 * Companion to [BreadthGateCompileTest], which covers the k-of-N cross-symbol breadth gate.
 */
class ForgeThesisExpressibilityTest {
    private fun parse(src: String): ParseResult.Success<com.qkt.dsl.ast.StrategyAst> {
        val r = Dsl.parse(src)
        if (r is ParseResult.Failure) error("parse failed: ${r.errors}")
        return r as ParseResult.Success
    }

    private fun ok(src: String) = assertThatCode { AstCompiler().compile(parse(src).value) }.doesNotThrowAnyException()

    @Test
    fun `ou_halflife via beta of change-on-lagged-level`() {
        // #606/#590/#595/#596: half_life = -ln(2)/b, b = slope of ds_t on s_{t-1}
        ok(
            """
            STRATEGY ou VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              aud = BACKTEST:AUDUSD EVERY 4h,
              nzd = BACKTEST:NZDUSD EVERY 4h
            LET spread = aud.close - 0.9812 * nzd.close,
                b = beta(spread - lag(spread, 1), lag(spread, 1), 96),
                halflife = 0 - 0.6931 / b
            RULES
              WHEN halflife > 0 AND halflife < 50 AND zscore(spread, 96) > 2 AND POSITION.aud = 0
              THEN SELL aud
            """.trimIndent(),
        )
    }

    @Test
    fun `bipower variation and jump stat via sma of abs-return products`() {
        // #580: BV = (pi/2)*mean(|r_t|*|r_{t-1}|); L_t = r_t / sqrt(BV)
        ok(
            """
            STRATEGY bipower VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              g = BACKTEST:XAUUSD EVERY 30m
            LET r = g.close - lag(g.close, 1),
                bv = 1.5708 * sma(abs(r) * abs(lag(g.close, 1) - lag(g.close, 2)), 48),
                jump = r / sqrt(bv)
            RULES
              WHEN jump > 4 AND POSITION.g = 0 THEN BUY g
            """.trimIndent(),
        )
    }

    @Test
    fun `portfolio realized variance via variance of weighted return sum`() {
        // #591: Var(sum w_i r_i) captures the covariance terms for free
        ok(
            """
            STRATEGY portvar VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              xau = BACKTEST:XAUUSD EVERY 1d,
              eur = BACKTEST:EURUSD EVERY 1d,
              aud = BACKTEST:AUDUSD EVERY 1d,
              nzd = BACKTEST:NZDUSD EVERY 1d
            LET rx = (xau.close - lag(xau.close, 1)) / lag(xau.close, 1),
                re = (eur.close - lag(eur.close, 1)) / lag(eur.close, 1),
                ra = (aud.close - lag(aud.close, 1)) / lag(aud.close, 1),
                rn = (nzd.close - lag(nzd.close, 1)) / lag(nzd.close, 1),
                pvol = sqrt(variance(rx + re + ra + rn, 20))
            RULES
              WHEN pvol > 0.02 AND POSITION.xau != 0 THEN FLATTEN
            """.trimIndent(),
        )
    }

    @Test
    fun `turn-of-month recurring window via NOW day accessors`() {
        // #605/#600/#599/#588/#585
        ok(
            """
            STRATEGY tom VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              eur = BACKTEST:EURUSD EVERY 1h
            RULES
              WHEN (NOW.day <= 3 OR NOW.day >= NOW.days_in_month - 1) AND POSITION.eur = 0
              THEN BUY eur
            """.trimIndent(),
        )
    }

    @Test
    fun `rolling fraction-of-condition via sma of a CASE indicator`() {
        // #602: share of hot bars over a window
        ok(
            """
            STRATEGY frac VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              eur = BACKTEST:EURUSD EVERY 30m
            LET hot = CASE WHEN (eur.high - eur.low) > 1.5 * atr(eur.candle, 14) THEN 1 ELSE 0 END,
                share = sma(hot, 96)
            RULES
              WHEN share < 0.3 AND POSITION.eur = 0 THEN BUY eur
            """.trimIndent(),
        )
    }

    @Test
    fun `sign-concordance via CASE sign sum`() {
        // #604: 1 - |sum(sign)|/N
        ok(
            """
            STRATEGY conc VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              aud = BACKTEST:AUDUSD EVERY 1h,
              nzd = BACKTEST:NZDUSD EVERY 1h,
              eur = BACKTEST:EURUSD EVERY 1h,
              gbp = BACKTEST:GBPUSD EVERY 1h
            LET sn = CASE WHEN nzd.close - lag(nzd.close, 1) > 0 THEN 1 ELSE 0 - 1 END,
                se = CASE WHEN eur.close - lag(eur.close, 1) > 0 THEN 1 ELSE 0 - 1 END,
                sg = CASE WHEN gbp.close - lag(gbp.close, 1) > 0 THEN 1 ELSE 0 - 1 END,
                disp = 1 - abs(sn + se + sg) / 3
            RULES
              WHEN disp > 0.6 AND POSITION.aud = 0 THEN SELL aud
            """.trimIndent(),
        )
    }

    @Test
    fun `breadth rate-of-change via lag of a CASE-sum`() {
        // #601: breadth expanding vs prior window
        ok(
            """
            STRATEGY breadthroc VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              eur = BACKTEST:EURUSD EVERY 4h,
              gbp = BACKTEST:GBPUSD EVERY 4h,
              aud = BACKTEST:AUDUSD EVERY 4h,
              nzd = BACKTEST:NZDUSD EVERY 4h
            LET be = CASE WHEN eur.close > ema(eur.close, 50) THEN 1 ELSE 0 END,
                bg = CASE WHEN gbp.close > ema(gbp.close, 50) THEN 1 ELSE 0 END,
                ba = CASE WHEN aud.close > ema(aud.close, 50) THEN 1 ELSE 0 END,
                bn = CASE WHEN nzd.close > ema(nzd.close, 50) THEN 1 ELSE 0 END,
                breadth = be + bg + ba + bn,
                roc = breadth - lag(breadth, 6)
            RULES
              WHEN roc > 0 AND breadth >= 3 AND POSITION.eur = 0 THEN BUY eur
            """.trimIndent(),
        )
    }
}
