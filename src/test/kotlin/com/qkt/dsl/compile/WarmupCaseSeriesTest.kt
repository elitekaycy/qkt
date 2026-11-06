package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Warm-up for an indicator fed a CASE expression gates on the stream its ELSE branch reads. */
class WarmupCaseSeriesTest {
    private fun warmup(cond: String) =
        WarmupRequirements.compute(
            (
                Dsl.parse(
                    "STRATEGY s VERSION 1\nSYMBOLS\n    b = BACKTEST:BTCUSDT EVERY 1m\n" +
                        "RULES\n    WHEN $cond THEN BUY b SIZING 1\n",
                ) as ParseResult.Success
            ).value,
        )

    @Test
    fun `a CASE series takes its stream from the ELSE branch`() {
        assertThat(warmup("sma(CASE WHEN TRUE THEN 1 ELSE b.close END, 5) > 0")).containsKey("b")
        assertThat(warmup("sma(CASE WHEN TRUE THEN 1 ELSE b.close END, 5) > 0").getValue("b")).isGreaterThanOrEqualTo(5)
    }

    @Test
    fun `a series that reads no stream adds no stream warm-up`() {
        assertThat(warmup("sma(NOW.hour_utc, 5) > 0")).doesNotContainKey("b")
    }
}
