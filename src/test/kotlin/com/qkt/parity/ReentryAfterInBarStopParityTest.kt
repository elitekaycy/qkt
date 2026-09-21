package com.qkt.parity

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A bracket that is filled and stopped out between two rule evaluations (#1194). The entry rule
 * is gated on being flat, so it is false while the position is open even though no bar closed in
 * that time, and it must fire again on the next bar it is flat — live and in backtest alike.
 */
class ReentryAfterInBarStopParityTest {
    private val dsl =
        """
        STRATEGY reentry_after_in_bar_stop VERSION 1
        DEFAULTS { SIZING = 1 TIF = GTC }
        SYMBOLS
          x = BACKTEST:X EVERY 1m
        RULES
          WHEN x.close > 0 AND POSITION.x = 0
          THEN BUY x BRACKET { STOP LOSS BY 1, TAKE PROFIT BY 50 }
        """.trimIndent()

    /** Every minute opens at 100 and trades down to 98 inside the bar: each entry is stopped out in-bar. */
    private val tape =
        (0 until 5).flatMap { minute ->
            val start = minute * 60_000L
            listOf(
                Tick("BACKTEST:X", Money.of("100"), start + 1_000L),
                Tick("BACKTEST:X", Money.of("98"), start + 30_000L),
                Tick("BACKTEST:X", Money.of("100"), start + 59_000L),
            )
        }

    @Test
    fun `an entry gated on being flat fires again after its bracket is stopped out inside the bar`() {
        val result = DslParityHarness.run("reentry_after_in_bar_stop", dsl, tape)

        assertThat(result.live).isEqualTo(result.backtest)
        val entries = result.backtest.trades.count { it.side == "BUY" }
        assertThat(
            entries,
        ).`as`("one entry per bar the strategy was flat, not one for the whole run").isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `an entry with no flat gate keeps firing once per signal episode`() {
        val ungated = dsl.replace(" AND POSITION.x = 0", "")

        val result = DslParityHarness.run("reentry_after_in_bar_stop", ungated, tape)

        assertThat(result.live).isEqualTo(result.backtest)
        assertThat(result.backtest.trades.count { it.side == "BUY" }).isEqualTo(1)
    }
}
