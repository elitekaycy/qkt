package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class ForgeReproductionExpressibilityTest {
    @Test
    fun `light commodity ratio reversion composes with a fixed four bar hold`() {
        compiles(
            """
            STRATEGY light_commodity_ratio_reversion VERSION 1
            DEFAULTS { SIZING = 0.01 }
            SYMBOLS
              gold = BACKTEST:XAUUSD EVERY 30m WARMUP 250 BARS,
              lightCommodity = BACKTEST:LIGHTCMDUSD EVERY 30m WARMUP 250 BARS
            LET ratioZ = zscore(gold.close / lightCommodity.close, 250)
            RULES
              WHEN ratioZ >= 2 AND POSITION.gold = 0 THEN SELL gold
              WHEN ratioZ <= -2 AND POSITION.gold = 0 THEN BUY gold
              WHEN POSITION.gold != 0 AND POSITION.gold.holding_duration >= 4 * 30 * 60
              THEN CLOSE gold
            """.trimIndent(),
        )
    }

    @Test
    fun `gold silver ratio continuation composes with a fixed twelve bar hold`() {
        compiles(
            """
            STRATEGY gold_silver_ratio_continuation VERSION 1
            DEFAULTS { SIZING = 0.01 }
            SYMBOLS
              gold = BACKTEST:XAUUSD EVERY 4h WARMUP 120 BARS,
              silver = BACKTEST:XAGUSD EVERY 4h WARMUP 120 BARS
            LET ratioZ = zscore(gold.close / silver.close, 120)
            RULES
              WHEN ratioZ >= 1 AND POSITION.gold = 0 THEN BUY gold
              WHEN POSITION.gold != 0 AND POSITION.gold.holding_duration >= 12 * 4 * 60 * 60
              THEN CLOSE gold
            """.trimIndent(),
        )
    }

    @Test
    fun `gold silver ratio stretch fade composes with a fixed twelve bar hold`() {
        compiles(
            """
            STRATEGY gold_silver_ratio_stretch_fade VERSION 1
            DEFAULTS { SIZING = 0.01 }
            SYMBOLS
              gold = BACKTEST:XAUUSD EVERY 1h WARMUP 250 BARS,
              silver = BACKTEST:XAGUSD EVERY 1h WARMUP 250 BARS
            LET ratioZ = zscore(gold.close / silver.close, 250)
            RULES
              WHEN ratioZ >= 2 AND POSITION.gold = 0 THEN SELL gold
              WHEN ratioZ <= -2 AND POSITION.gold = 0 THEN BUY gold
              WHEN POSITION.gold != 0 AND POSITION.gold.holding_duration >= 12 * 60 * 60
              THEN CLOSE gold
            """.trimIndent(),
        )
    }

    private fun compiles(source: String) {
        assertThatCode {
            val parsed = Dsl.parse(source)
            require(parsed is ParseResult.Success) {
                "parse failed: ${(parsed as ParseResult.Failure).errors}"
            }
            AstCompiler().compile(parsed.value)
        }.doesNotThrowAnyException()
    }
}
