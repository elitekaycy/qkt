package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.parity.ReentryReplayFixtures.ONE_MINUTE_MS
import com.qkt.parity.ReentryReplayFixtures.STARTING_BALANCE
import com.qkt.parity.ReentryReplayFixtures.candle
import com.qkt.parity.ReentryReplayFixtures.withQuietParityLogs
import com.qkt.parity.ReentryStrategyFiles.writeLossStreakResetStrategy
import com.qkt.parity.ReentryStrategyFiles.writePendingReentryStrategy
import com.qkt.parity.ReentryStrategyFiles.writeStrategy
import com.qkt.risk.StrategyRiskLimits
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReentryLifecycleParityTest {
    @Test
    fun `strategy reenters after close when condition becomes true again across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeStrategy(tempDir, "reentry_allowed")

        val result =
            withQuietParityLogs {
                GeneratedStrategyReplay.assertTickBarAndLiveParity(
                    path = strategyPath,
                    closes = listOf("100", "101", "102", "90", "90", "105", "106", "90"),
                    expectedTradeCount = 4,
                    startingBalance = STARTING_BALANCE,
                )
            }

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "90", "106", "90")
        assertThat(result.backtest.rejections).isEmpty()
        assertThat(result.backtest.halts).isEmpty()
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `pending entry guard prevents duplicate orders before allowed reentry across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writePendingReentryStrategy(tempDir, "reentry_pending_guard")

        val result =
            withQuietParityLogs {
                GeneratedStrategyReplay.assertTickBarAndLiveParity(
                    path = strategyPath,
                    closes = listOf("100", "101", "102", "94", "110", "100", "101", "94", "110"),
                    expectedTradeCount = 4,
                    startingBalance = STARTING_BALANCE,
                )
            }

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.quantity }).containsOnly("1")
        assertThat(result.backtest.rejections).isEmpty()
        assertThat(result.backtest.halts).isEmpty()
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `winning lifecycle resets loss streak before later reentry across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeLossStreakResetStrategy(tempDir, "reentry_loss_streak_reset")

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
                                    candle("120", 4 * ONE_MINUTE_MS),
                                    candle("121", 5 * ONE_MINUTE_MS),
                                    candle("140", 6 * ONE_MINUTE_MS),
                                    candle("141", 7 * ONE_MINUTE_MS),
                                    candle("150", 8 * ONE_MINUTE_MS),
                                    candle("151", 9 * ONE_MINUTE_MS),
                                    candle("130", 10 * ONE_MINUTE_MS),
                                    candle("131", 11 * ONE_MINUTE_MS),
                                    candle("160", 12 * ONE_MINUTE_MS),
                                    candle("161", 13 * ONE_MINUTE_MS),
                                    candle("170", 14 * ONE_MINUTE_MS),
                                    candle("171", 15 * ONE_MINUTE_MS),
                                ),
                        ),
                    window = TimeWindow.ONE_MINUTE,
                    closeOnlyTicks = true,
                    expectedTradeCount = 8,
                    startingBalance = STARTING_BALANCE,
                    strategyRiskLimits = StrategyRiskLimits(lossStreakHalt = 2),
                )
            }

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side })
            .containsExactly("BUY", "SELL", "BUY", "SELL", "BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price })
            .containsExactly("101", "91", "121", "141", "151", "131", "161", "171")
        assertThat(result.backtest.rejections).isEmpty()
        assertThat(result.backtest.halts).isEmpty()
        assertThat(result.backtest.positions).isEmpty()
    }
}
