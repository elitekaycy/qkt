package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.parity.ReentryReplayFixtures.DAY_MS
import com.qkt.parity.ReentryReplayFixtures.STARTING_BALANCE
import com.qkt.parity.ReentryReplayFixtures.dailyHaltResetCandles
import com.qkt.parity.ReentryReplayFixtures.withQuietParityLogs
import com.qkt.parity.ReentryStrategyFiles.writeDailyHaltResetStrategy
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.HaltRules
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReentryGlobalDailyHaltParityTest {
    @Test
    fun `global daily loss halt rejects same day reentry and resumes next UTC day across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeDailyHaltResetStrategy(tempDir, "reentry_global_daily_loss_reset")

        val result =
            withQuietParityLogs {
                GeneratedStrategyReplay.assertTickBarAndLiveParity(
                    path = strategyPath,
                    candlesBySymbol = dailyHaltResetCandles(),
                    window = TimeWindow.ONE_MINUTE,
                    closeOnlyTicks = true,
                    expectedTradeCount = 4,
                    expectedRejectionCount = 1,
                    expectedHaltCount = 1,
                    startingBalance = STARTING_BALANCE,
                    haltRules = { HaltRules.standard(maxDailyLoss = BigDecimal("5")) },
                )
            }

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "91", "121", "141")
        assertThat(
            result.backtest.halts
                .single()
                .reason,
        ).contains("daily loss")
        assertThat(
            result.backtest.halts
                .single()
                .strategyId,
        ).isNull()
        assertThat(
            result.backtest.rejections
                .single()
                .reason,
        ).contains("daily loss")
        assertThat(
            result.backtest.rejections
                .single()
                .timestamp,
        ).isLessThan(DAY_MS)
        assertThat(result.backtest.trades[2].timestamp).isGreaterThanOrEqualTo(DAY_MS)
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `global daily drawdown halt rejects same day reentry and resumes next UTC day across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeDailyHaltResetStrategy(tempDir, "reentry_global_daily_drawdown_reset")

        val result =
            withQuietParityLogs {
                GeneratedStrategyReplay.assertTickBarAndLiveParity(
                    path = strategyPath,
                    candlesBySymbol = dailyHaltResetCandles(),
                    window = TimeWindow.ONE_MINUTE,
                    closeOnlyTicks = true,
                    expectedTradeCount = 4,
                    expectedRejectionCount = 1,
                    expectedHaltCount = 1,
                    startingBalance = STARTING_BALANCE,
                    dailyDdBasis = DailyDrawdownBasis.EQUITY,
                    haltRules = {
                        HaltRules.standard(
                            maxDailyLoss = BigDecimal.ZERO,
                            maxDailyDrawdownPct = BigDecimal("0.005"),
                            startingBalance = STARTING_BALANCE,
                        )
                    },
                )
            }

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "91", "121", "141")
        assertThat(
            result.backtest.halts
                .single()
                .reason,
        ).contains("daily drawdown")
        assertThat(
            result.backtest.halts
                .single()
                .strategyId,
        ).isNull()
        assertThat(
            result.backtest.rejections
                .single()
                .reason,
        ).contains("daily drawdown")
        assertThat(
            result.backtest.rejections
                .single()
                .timestamp,
        ).isLessThan(DAY_MS)
        assertThat(result.backtest.trades[2].timestamp).isGreaterThanOrEqualTo(DAY_MS)
        assertThat(result.backtest.positions).isEmpty()
    }
}
