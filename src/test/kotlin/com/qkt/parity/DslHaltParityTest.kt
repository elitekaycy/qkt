package com.qkt.parity

import com.qkt.parity.DslFeatureParityFixtures.tape
import com.qkt.risk.rules.MaxDailyLoss
import com.qkt.risk.rules.MaxDrawdown
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DslHaltParityTest {
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
}
