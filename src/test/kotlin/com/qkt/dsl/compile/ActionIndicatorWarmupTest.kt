package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An indicator read only inside an action's clauses still warms the action's stream. */
class ActionIndicatorWarmupTest {
    private fun warmupOf(action: String): Int =
        WarmupRequirements
            .compute(
                (
                    Dsl.parse(
                        "STRATEGY s VERSION 1\nSYMBOLS\n    b = BACKTEST:BTCUSDT EVERY 1m\nRULES\n" +
                            "    WHEN b.close > 0 THEN $action\n",
                    ) as ParseResult.Success
                ).value,
            ).getOrDefault("b", 0)

    @Test
    fun `indicators in TIMES, OCO, stack spacing and layers, STACK_AT and order types count`() {
        val actions =
            mapOf(
                "BUY b SIZING 1 TIMES sma(b.close, 13)" to 13,
                "BUY b SIZING 1 OCO { STOP AT sma(b.close, 17), LIMIT AT sma(b.close, 19) }" to 19,
                "BUY b SIZING 1 STACK 2 SPACING sma(b.close, 23)" to 23,
                "BUY b STACK [ 0.1, 0.2 LIMIT AT sma(b.close, 29) ]" to 29,
                "BUY b SIZING 1 ORDER_TYPE = STOP AT b.close LIMIT AT sma(b.close, 31)" to 31,
                "SELL b SIZING 1 ORDER_TYPE = TRAILING PCT sma(b.close, 37) / 100" to 37,
            )

        for ((action, period) in actions) {
            assertThat(warmupOf(action)).describedAs(action).isGreaterThanOrEqualTo(period)
        }
    }

    @Test
    fun `indicators in a STACK_AT tier's recover distance and bracket count`() {
        val stackAt =
            "BUY b SIZING 0.2 BRACKET { STOP LOSS BY 25, TAKE PROFIT BY 60 } " +
                "STACK_AT MAE >= 10 RECOVER sma(b.close, 41) WITHIN 1h SIZING 0.1 " +
                "BRACKET { STOP LOSS BY sma(b.close, 43), TAKE PROFIT BY sma(b.close, 47) }"

        assertThat(warmupOf(stackAt)).isGreaterThanOrEqualTo(47)
    }
}
