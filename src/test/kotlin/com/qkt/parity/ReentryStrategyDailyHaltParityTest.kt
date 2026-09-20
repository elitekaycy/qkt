package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.parity.ReentryReplayFixtures.DAY_MS
import com.qkt.parity.ReentryReplayFixtures.ONE_MINUTE_MS
import com.qkt.parity.ReentryReplayFixtures.STARTING_BALANCE
import com.qkt.parity.ReentryReplayFixtures.candle
import com.qkt.parity.ReentryReplayFixtures.withQuietParityLogs
import com.qkt.parity.ReentryStrategyFiles.writeDailyHaltResetStrategy
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.StrategyRiskLimits
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReentryStrategyDailyHaltParityTest {
    @Test
    fun `daily loss halt rejects same day reentry and resumes next UTC day across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeDailyHaltResetStrategy(tempDir, "reentry_daily_loss_reset")

        val result =
            withQuietParityLogs {
                GeneratedStrategyReplay.assertTickBarAndLiveParity(
                    path = strategyPath,
                    candlesBySymbol =
                        mapOf(
                            "BACKTEST:X" to
                                listOf(
                                    candle("100", DAY_MS - 10 * ONE_MINUTE_MS),
                                    candle("101", DAY_MS - 9 * ONE_MINUTE_MS),
                                    candle("90", DAY_MS - 8 * ONE_MINUTE_MS),
                                    candle("91", DAY_MS - 7 * ONE_MINUTE_MS),
                                    candle("110", DAY_MS - 6 * ONE_MINUTE_MS),
                                    candle("111", DAY_MS - 5 * ONE_MINUTE_MS),
                                    candle("120", DAY_MS + ONE_MINUTE_MS),
                                    candle("121", DAY_MS + 2 * ONE_MINUTE_MS),
                                    candle("140", DAY_MS + 3 * ONE_MINUTE_MS),
                                    candle("141", DAY_MS + 4 * ONE_MINUTE_MS),
                                ),
                        ),
                    window = TimeWindow.ONE_MINUTE,
                    closeOnlyTicks = true,
                    expectedTradeCount = 4,
                    expectedRejectionCount = 1,
                    expectedHaltCount = 1,
                    startingBalance = STARTING_BALANCE,
                    strategyRiskLimits = StrategyRiskLimits(maxDailyLoss = BigDecimal("5")),
                )
            }

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "91", "121", "141")
        assertThat(
            result.backtest.halts
                .single()
                .reason,
        ).contains("strategy daily loss")
        assertThat(
            result.backtest.halts
                .single()
                .strategyId,
        ).isEqualTo("reentry_daily_loss_reset")
        assertThat(
            result.backtest.rejections
                .single()
                .reason,
        ).contains("strategy daily loss")
        assertThat(
            result.backtest.rejections
                .single()
                .timestamp,
        ).isLessThan(DAY_MS)
        assertThat(result.backtest.trades[2].timestamp).isGreaterThanOrEqualTo(DAY_MS)
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `daily drawdown halt rejects same day reentry and resumes next UTC day across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeDailyHaltResetStrategy(tempDir, "reentry_daily_drawdown_reset")

        val result =
            withQuietParityLogs {
                GeneratedStrategyReplay.assertTickBarAndLiveParity(
                    path = strategyPath,
                    candlesBySymbol =
                        mapOf(
                            "BACKTEST:X" to
                                listOf(
                                    candle("100", DAY_MS - 10 * ONE_MINUTE_MS),
                                    candle("101", DAY_MS - 9 * ONE_MINUTE_MS),
                                    candle("90", DAY_MS - 8 * ONE_MINUTE_MS),
                                    candle("91", DAY_MS - 7 * ONE_MINUTE_MS),
                                    candle("110", DAY_MS - 6 * ONE_MINUTE_MS),
                                    candle("111", DAY_MS - 5 * ONE_MINUTE_MS),
                                    candle("120", DAY_MS + ONE_MINUTE_MS),
                                    candle("121", DAY_MS + 2 * ONE_MINUTE_MS),
                                    candle("140", DAY_MS + 3 * ONE_MINUTE_MS),
                                    candle("141", DAY_MS + 4 * ONE_MINUTE_MS),
                                ),
                        ),
                    window = TimeWindow.ONE_MINUTE,
                    closeOnlyTicks = true,
                    expectedTradeCount = 4,
                    expectedRejectionCount = 1,
                    expectedHaltCount = 1,
                    startingBalance = STARTING_BALANCE,
                    strategyRiskLimits = StrategyRiskLimits(maxDailyDrawdownPct = BigDecimal("0.005")),
                    dailyDdBasis = DailyDrawdownBasis.EQUITY,
                )
            }

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "91", "121", "141")
        assertThat(
            result.backtest.halts
                .single()
                .reason,
        ).contains("strategy daily drawdown")
        assertThat(
            result.backtest.halts
                .single()
                .strategyId,
        ).isEqualTo("reentry_daily_drawdown_reset")
        assertThat(
            result.backtest.rejections
                .single()
                .reason,
        ).contains("strategy daily drawdown")
        assertThat(
            result.backtest.rejections
                .single()
                .timestamp,
        ).isLessThan(DAY_MS)
        assertThat(result.backtest.trades[2].timestamp).isGreaterThanOrEqualTo(DAY_MS)
        assertThat(result.backtest.positions).isEmpty()
    }
}
