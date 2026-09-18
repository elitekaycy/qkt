package com.qkt.parity

import com.qkt.parity.DslFeatureParityFixtures.assertParity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DslPositionFeatureParityTest {
    @Test
    fun `CLOSE rule has full-state parity`() {
        val result =
            assertParity(
                "close_rule",
                """
                STRATEGY close_rule VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close = 100 AND POSITION.btc = 0 THEN BUY btc
                  WHEN btc.close = 110 AND POSITION.btc > 0 THEN CLOSE btc
                """.trimIndent(),
                listOf("100", "100", "105", "110", "111", "111"),
            )

        assertThat(result.backtest.positions).isEmpty()
        assertThat(result.backtest.pnl.realized).isNotEqualTo("0")
    }

    @Test
    fun `RESIZE grow shrink and flatten has full-state parity`() {
        val result =
            assertParity(
                "resize",
                """
                STRATEGY resize VERSION 1
                DEFAULTS { SIZING = 0.01 TIF = GTC }
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close = 100 AND POSITION.btc = 0 THEN BUY btc
                  WHEN btc.close = 110 THEN RESIZE btc TO 0.03
                  WHEN btc.close = 120 THEN RESIZE btc TO 0.01
                  WHEN btc.close = 130 THEN RESIZE btc TO 0
                """.trimIndent(),
                listOf("100", "100", "110", "120", "130", "130"),
            )

        assertThat(result.backtest.trades).hasSize(4)
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `latch retrace bracket has full-state parity`() {
        assertParity(
            "latch",
            """
            STRATEGY latch VERSION 1
            SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
              WHEN btc.close = 100 AND POSITION.btc = 0
              THEN LATCH btc OFFSET 1 ARM 5m {
                ENTER LIMIT RETRACE 2
                  BRACKET { STOP LOSS AGAINST 5, TAKE PROFIT WITH 5 }
                  SIZING 1
                  EXPIRE 2h
              }
            """.trimIndent(),
            listOf("100", "100", "102", "99", "106", "106"),
        )
    }

    @Test
    fun `conditional stack has full-state parity`() {
        val result =
            assertParity(
                "stack",
                """
                STRATEGY stack VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close = 100 AND POSITION.btc = 0
                  THEN BUY btc
                    STACK_AT MFE >= 5 WITHIN 30m SIZING 0.5
                    BRACKET { STOP LOSS BY 3, TAKE PROFIT BY 10 }
                  WHEN btc.close = 112 THEN CLOSE btc
                """.trimIndent(),
                listOf("100", "100", "106", "108", "112", "113", "113"),
            )

        assertThat(result.backtest.trades.size).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `exit hook has full-state paper parity`() {
        val result =
            assertParity(
                "exit_hook",
                """
                STRATEGY exit_hook VERSION 1
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close = 100 AND POSITION.btc = 0
                  THEN BUY btc SIZING 1
                    BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 50 }
                    ON_STOP {
                      SELL btc SIZING EXIT.qty
                        BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 }
                    }
                """.trimIndent(),
                listOf("100", "100", "94", "94", "90", "90"),
            )

        assertThat(result.backtest.trades).hasSizeGreaterThanOrEqualTo(3)
        assertThat(result.live).isEqualTo(result.backtest)
    }
}
