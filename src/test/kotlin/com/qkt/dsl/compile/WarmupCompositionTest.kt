package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarmupCompositionTest {
    private fun ast(src: String) = (Dsl.parse(src) as ParseResult.Success).value

    @Test
    fun `session indicators derive a time-horizon warmup in stream bars`() {
        val ast =
            ast(
                """
                STRATEGY s VERSION 1
                SYMBOLS
                  x = EXNESS:XAUUSD EVERY 1h
                RULES
                  WHEN session_momentum(x.candle, 12, 14, 2) > 0 THEN FLATTEN
                """.trimIndent(),
            )

        assertThat(WarmupRequirements.compute(ast)["x"]).isEqualTo(49)
    }

    @Test
    fun `a SINCE T-N window needs N bars of warmup on its stream (#1130)`() {
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m,
                  h = X:Z EVERY 5m
                LET m = mean(g.close) SINCE T-200,
                    c = count(h.close > h.open, 30)
                RULES
                  WHEN m > 0 AND c > 10 THEN FLATTEN
                """.trimIndent(),
            )
        val req = WarmupRequirements.compute(s)
        assertThat(req["g"]).isEqualTo(200)
        assertThat(req["h"]).isEqualTo(30)
    }

    @Test
    fun `MACD warmup is the indicator's true requirement, not the max literal`() {
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m
                RULES
                  WHEN macd(g.close, 12, 26, 9) > 0 THEN FLATTEN
                """.trimIndent(),
            )
        // The slow EMA needs 26 bars and the signal line 9 more on top — the max
        // literal (26) under-warms by 8 bars. Read the truth from the indicator.
        val derived = WarmupRequirements.compute(s).getValue("g")
        val actual =
            com.qkt.dsl.stdlib.IndicatorRegistry
                .create(
                    "MACD",
                    listOf(java.math.BigDecimal(12), java.math.BigDecimal(26), java.math.BigDecimal(9)),
                ).warmupBars
        assertThat(derived).isEqualTo(actual)
        assertThat(derived).isGreaterThan(26)
    }

    @Test
    fun `action-side indicators count toward warmup`() {
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m
                RULES
                  WHEN g.close > 0
                  THEN BUY g SIZING 1 BRACKET { STOP LOSS BY atr(g.candle, 50), TAKE PROFIT BY atr(g.candle, 50) }
                """.trimIndent(),
            )
        // The ATR lives only in the bracket child price — it computes garbage on a
        // half-warm window exactly like a condition-side indicator would.
        assertThat(WarmupRequirements.compute(s).getValue("g")).isGreaterThanOrEqualTo(50)
    }

    @Test
    fun `chained indicators compose the inner depth onto the outer period`() {
        // ema(9) is undefined for its first 9 closes, so the outer ema(21) only starts
        // filling on bar 10: the true requirement is 30, not the outer period alone.
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m
                RULES
                  WHEN ema(ema(g.close, 9), 21) > g.close THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(WarmupRequirements.compute(s)).containsExactly(java.util.Map.entry("g", 30))
    }

    @Test
    fun `indicator over boolean dwell condition derives warmup through let references`() {
        val s =
            ast(
                """
                STRATEGY dwell VERSION 1
                SYMBOLS
                  g = X:Y EVERY 30m
                LET calm = atr(g.candle, 14) < percentile_rank(atr(g.candle, 14), 200)
                RULES
                  WHEN zscore(runlength_where(calm), 20) > 1 THEN FLATTEN
                """.trimIndent(),
            )

        assertThat(WarmupRequirements.compute(s).getValue("g")).isGreaterThanOrEqualTo(200)
    }

    @Test
    fun `nested indicators inside an expression-fed series add their depth to the outer window`() {
        // bot2's s5 shape: a 160-bar percentile over an expression that itself needs lag(.., 160)
        // on both streams. The window cannot start filling until the lags are defined, so the
        // true requirement is 160 + 161 on every stream the inner expression reads — declaring
        // WARMUP 260 left that child cold for 60 live bars after deploy.
        val s =
            (
                Dsl.parse(
                    """
                    STRATEGY t VERSION 1
                    SYMBOLS
                      gold = EXNESS:XAUUSD EVERY 1h,
                      silver = EXNESS:XAGUSD EVERY 1h
                    RULES
                      WHEN percentile_rank(((gold.close/lag(gold.close,80))/(silver.close/lag(silver.close,80))-1)-((lag(gold.close,80)/lag(gold.close,160))/(lag(silver.close,80)/lag(silver.close,160))-1),160) <= 0.05
                      THEN FLATTEN
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value
        val req = WarmupRequirements.compute(s)
        assertThat(req["gold"]).isGreaterThanOrEqualTo(320)
        assertThat(req["silver"]).isGreaterThanOrEqualTo(320)
    }

    @Test
    fun `cross-timeframe nesting converts the outer span into the slow stream's bars`() {
        // The outer window advances on the 1m primary; lag(o, 2) on the 5m stream needs
        // 3 closes and must then stay defined for 5 primary minutes = one more 5m bar.
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  s = X:A EVERY 1m,
                  o = X:B EVERY 5m
                RULES
                  WHEN percentile_rank(s.close / lag(o.close, 2), 5) >= 0 THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(WarmupRequirements.compute(s))
            .containsExactlyInAnyOrderEntriesOf(mapOf("s" to 5, "o" to 4))
    }
}
