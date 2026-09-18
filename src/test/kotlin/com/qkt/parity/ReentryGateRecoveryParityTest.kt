package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.parity.ReentryReplayFixtures.DAY_MS
import com.qkt.parity.ReentryReplayFixtures.ONE_MINUTE_MS
import com.qkt.parity.ReentryReplayFixtures.STARTING_BALANCE
import com.qkt.parity.ReentryReplayFixtures.TEN_MINUTES_MS
import com.qkt.parity.ReentryReplayFixtures.candle
import com.qkt.parity.ReentryReplayFixtures.withQuietParityLogs
import com.qkt.parity.ReentryStrategyFiles.writeTimedReentryStrategy
import com.qkt.risk.StrategyRiskLimits
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReentryGateRecoveryParityTest {
    @Test
    fun `max trades reentry gate resets at UTC day boundary across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeTimedReentryStrategy(tempDir, "reentry_max_trades_next_day")

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
                                    candle("80", DAY_MS + 3 * ONE_MINUTE_MS),
                                    candle("81", DAY_MS + 4 * ONE_MINUTE_MS),
                                ),
                        ),
                    window = TimeWindow.ONE_MINUTE,
                    closeOnlyTicks = true,
                    expectedTradeCount = 4,
                    expectedRejectionCount = 1,
                    startingBalance = STARTING_BALANCE,
                    strategyRiskLimits = StrategyRiskLimits(maxTradesPerDay = 1),
                )
            }

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "91", "121", "81")
        assertThat(
            result.backtest.rejections
                .single()
                .reason,
        ).contains("MaxTradesPerDay")
        assertThat(
            result.backtest.rejections
                .single()
                .timestamp,
        ).isLessThan(DAY_MS)
        assertThat(result.backtest.trades[2].timestamp).isGreaterThanOrEqualTo(DAY_MS)
        assertThat(result.backtest.halts).isEmpty()
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `cooldown reentry gate recovers after elapsed duration across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeTimedReentryStrategy(tempDir, "reentry_cooldown_recovered")

        val result =
            withQuietParityLogs {
                GeneratedStrategyReplay.assertTickBarAndLiveParity(
                    path = strategyPath,
                    candlesBySymbol =
                        mapOf(
                            "BACKTEST:X" to
                                listOf(
                                    candle("100", 0),
                                    candle("101", ONE_MINUTE_MS),
                                    candle("90", 2 * ONE_MINUTE_MS),
                                    candle("91", 3 * ONE_MINUTE_MS),
                                    candle("110", 4 * ONE_MINUTE_MS),
                                    candle("111", 5 * ONE_MINUTE_MS),
                                    candle("120", 15 * ONE_MINUTE_MS),
                                    candle("121", 16 * ONE_MINUTE_MS),
                                    candle("80", 17 * ONE_MINUTE_MS),
                                    candle("81", 18 * ONE_MINUTE_MS),
                                ),
                        ),
                    window = TimeWindow.ONE_MINUTE,
                    closeOnlyTicks = true,
                    expectedTradeCount = 4,
                    expectedRejectionCount = 1,
                    startingBalance = STARTING_BALANCE,
                    strategyRiskLimits = StrategyRiskLimits(cooldownAfterLossMs = TEN_MINUTES_MS),
                )
            }

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "91", "121", "81")
        assertThat(
            result.backtest.rejections
                .single()
                .reason,
        ).contains("CooldownAfterLoss")
        assertThat(
            result.backtest.rejections
                .single()
                .timestamp,
        ).isLessThan(result.backtest.trades[2].timestamp)
        assertThat(result.backtest.trades[2].timestamp - result.backtest.trades[1].timestamp)
            .isGreaterThanOrEqualTo(TEN_MINUTES_MS)
        assertThat(result.backtest.halts).isEmpty()
        assertThat(result.backtest.positions).isEmpty()
    }
}
