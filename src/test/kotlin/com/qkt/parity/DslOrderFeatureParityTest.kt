package com.qkt.parity

import com.qkt.parity.DslFeatureParityFixtures.assertParity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DslOrderFeatureParityTest {
    @Test
    fun `trailing entry has full-state parity`() {
        assertParity(
            "trailing",
            """
            STRATEGY trailing VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
              WHEN btc.close = 100 AND POSITION.btc = 0
              THEN BUY btc ORDER_TYPE = TRAILING BY 10
            """.trimIndent(),
            listOf("100", "98", "95", "92", "90", "85", "80", "82", "88", "95", "100", "100"),
        )
    }

    @Test
    fun `GTD expiry wins before a later limit cross in both modes`() {
        val result =
            assertParity(
                "gtd",
                """
                STRATEGY gtd VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close = 100 AND POSITION.btc = 0
                  THEN BUY btc ORDER_TYPE = LIMIT AT 90 TIF GTD NOW + 2m
                  WHEN btc.close = 110 AND POSITION.btc = 0 THEN BUY btc
                """.trimIndent(),
                listOf("100", "100", "95", "89", "110", "111", "111"),
            )

        assertThat(result.backtest.trades).hasSize(1)
        val onlyTrade = result.backtest.trades.single()
        assertThat(onlyTrade.price).isEqualTo("111")
    }

    @Test
    fun `OPEN_ORDERS blocks duplicate entries until GTD expiry in both modes`() {
        val result =
            assertParity(
                "open_orders",
                """
                STRATEGY open_orders VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close >= 100
                   AND POSITION.btc = 0
                   AND OPEN_ORDERS.btc = 0
                  THEN BUY btc ORDER_TYPE = LIMIT AT 90 TIF GTD NOW + 4m
                """.trimIndent(),
                listOf("100", "100", "99", "101", "99", "101", "89", "89"),
            )

        assertThat(result.backtest.trades).hasSize(1)
        assertThat(
            result.backtest.trades
                .single()
                .orderId,
        ).endsWith("--1")
    }
}
