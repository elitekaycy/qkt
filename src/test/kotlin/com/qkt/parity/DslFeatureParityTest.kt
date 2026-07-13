package com.qkt.parity

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.risk.rules.MaxDailyLoss
import com.qkt.risk.rules.MaxDrawdown
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DslFeatureParityTest {
    private val symbol = "BACKTEST:BTCUSDT"
    private val initialTs = 1_700_000_000_000L

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
    fun `realized daily-loss halt has matching event tick`() {
        val result =
            DslParityHarness.run(
                strategyId = "daily_loss",
                source =
                    """
                    STRATEGY daily_loss VERSION 1
                    DEFAULTS { SIZING = 1 TIF = GTC }
                    SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                    RULES
                      WHEN btc.close = 100 AND POSITION.btc = 0 THEN BUY btc
                      WHEN btc.close = 90 AND POSITION.btc > 0 THEN CLOSE btc
                    """.trimIndent(),
                ticks = tape(listOf("100", "100", "90", "90", "90")),
                haltRules = { listOf(MaxDailyLoss(BigDecimal("5"))) },
            )

        assertThat(result.backtest.halts).hasSize(1)
        assertThat(result.live).isEqualTo(result.backtest)
    }

    @Test
    fun `unrealized drawdown halt has matching event tick`() {
        val result =
            DslParityHarness.run(
                strategyId = "drawdown",
                source =
                    """
                    STRATEGY drawdown VERSION 1
                    DEFAULTS { SIZING = 1 TIF = GTC }
                    SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                    RULES
                      WHEN btc.close = 100 AND POSITION.btc = 0 THEN BUY btc
                    """.trimIndent(),
                ticks = tape(listOf("100", "100", "90", "89", "89")),
                haltRules = {
                    listOf(
                        MaxDrawdown(
                            maxFraction = BigDecimal("0.0005"),
                            initialBalance = BigDecimal("10000"),
                        ),
                    )
                },
            )

        assertThat(result.backtest.halts).hasSize(1)
        assertThat(result.live).isEqualTo(result.backtest)
    }

    private fun assertParity(
        strategyId: String,
        source: String,
        prices: List<String>,
    ): DslParityHarness.Result {
        val result = DslParityHarness.run(strategyId, source, tape(prices))
        assertThat(result.backtest.trades).isNotEmpty
        assertThat(result.live).isEqualTo(result.backtest)
        return result
    }

    private fun tape(prices: List<String>): List<Tick> =
        prices.mapIndexed { index, price ->
            Tick(symbol, Money.of(price), initialTs + index * 60_000L)
        }
}
