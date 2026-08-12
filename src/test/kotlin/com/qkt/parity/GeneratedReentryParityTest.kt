package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.marketdata.Candle
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.DrawdownBasis
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltRules
import com.qkt.risk.StrategyRiskLimits
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class GeneratedReentryParityTest {
    private data class GateCase(
        val id: String,
        val expectedReason: String,
        val strategyRiskLimits: StrategyRiskLimits = StrategyRiskLimits(),
        val dailyDdBasis: DailyDrawdownBasis = DailyDrawdownBasis.BALANCE,
        val totalDdBasis: DrawdownBasis = DrawdownBasis.STATIC,
        val haltRules: () -> List<HaltRule> = { emptyList() },
        val expectedHaltStrategyId: String? = null,
    )

    @Test
    fun `strategy reenters after close when condition becomes true again across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeStrategy(tempDir, "reentry_allowed")

        val result =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = strategyPath,
                closes = listOf("100", "101", "102", "90", "90", "105", "106", "90"),
                expectedTradeCount = 4,
                startingBalance = STARTING_BALANCE,
            )

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
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = strategyPath,
                closes = listOf("100", "101", "102", "94", "110", "100", "101", "94", "110"),
                expectedTradeCount = 4,
                startingBalance = STARTING_BALANCE,
            )

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.quantity }).containsOnly("1")
        assertThat(result.backtest.rejections).isEmpty()
        assertThat(result.backtest.halts).isEmpty()
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `max trades reentry gate resets at UTC day boundary across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeTimedReentryStrategy(tempDir, "reentry_max_trades_next_day")

        val result =
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

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "91", "121", "81")
        assertThat(result.backtest.rejections.single().reason).contains("MaxTradesPerDay")
        assertThat(result.backtest.rejections.single().timestamp).isLessThan(DAY_MS)
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

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "91", "121", "81")
        assertThat(result.backtest.rejections.single().reason).contains("CooldownAfterLoss")
        assertThat(result.backtest.rejections.single().timestamp).isLessThan(result.backtest.trades[2].timestamp)
        assertThat(result.backtest.trades[2].timestamp - result.backtest.trades[1].timestamp)
            .isGreaterThanOrEqualTo(TEN_MINUTES_MS)
        assertThat(result.backtest.halts).isEmpty()
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `winning lifecycle resets loss streak before later reentry across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeLossStreakResetStrategy(tempDir, "reentry_loss_streak_reset")

        val result =
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

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side })
            .containsExactly("BUY", "SELL", "BUY", "SELL", "BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price })
            .containsExactly("101", "91", "121", "141", "151", "131", "161", "171")
        assertThat(result.backtest.rejections).isEmpty()
        assertThat(result.backtest.halts).isEmpty()
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `daily loss halt rejects same day reentry and resumes next UTC day across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeDailyHaltResetStrategy(tempDir, "reentry_daily_loss_reset")

        val result =
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

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "91", "121", "141")
        assertThat(result.backtest.halts.single().reason).contains("strategy daily loss")
        assertThat(result.backtest.halts.single().strategyId).isEqualTo("reentry_daily_loss_reset")
        assertThat(result.backtest.rejections.single().reason).contains("strategy daily loss")
        assertThat(result.backtest.rejections.single().timestamp).isLessThan(DAY_MS)
        assertThat(result.backtest.trades[2].timestamp).isGreaterThanOrEqualTo(DAY_MS)
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `daily drawdown halt rejects same day reentry and resumes next UTC day across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeDailyHaltResetStrategy(tempDir, "reentry_daily_drawdown_reset")

        val result =
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

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "91", "121", "141")
        assertThat(result.backtest.halts.single().reason).contains("strategy daily drawdown")
        assertThat(result.backtest.halts.single().strategyId).isEqualTo("reentry_daily_drawdown_reset")
        assertThat(result.backtest.rejections.single().reason).contains("strategy daily drawdown")
        assertThat(result.backtest.rejections.single().timestamp).isLessThan(DAY_MS)
        assertThat(result.backtest.trades[2].timestamp).isGreaterThanOrEqualTo(DAY_MS)
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `global daily loss halt rejects same day reentry and resumes next UTC day across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeDailyHaltResetStrategy(tempDir, "reentry_global_daily_loss_reset")

        val result =
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

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "91", "121", "141")
        assertThat(result.backtest.halts.single().reason).contains("daily loss")
        assertThat(result.backtest.halts.single().strategyId).isNull()
        assertThat(result.backtest.rejections.single().reason).contains("daily loss")
        assertThat(result.backtest.rejections.single().timestamp).isLessThan(DAY_MS)
        assertThat(result.backtest.trades[2].timestamp).isGreaterThanOrEqualTo(DAY_MS)
        assertThat(result.backtest.positions).isEmpty()
    }

    @Test
    fun `global daily drawdown halt rejects same day reentry and resumes next UTC day across replay modes`(
        @TempDir tempDir: Path,
    ) {
        val strategyPath = writeDailyHaltResetStrategy(tempDir, "reentry_global_daily_drawdown_reset")

        val result =
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

        assertThat(result.backtest).isEqualTo(result.live)
        assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL", "BUY", "SELL")
        assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "91", "121", "141")
        assertThat(result.backtest.halts.single().reason).contains("daily drawdown")
        assertThat(result.backtest.halts.single().strategyId).isNull()
        assertThat(result.backtest.rejections.single().reason).contains("daily drawdown")
        assertThat(result.backtest.rejections.single().timestamp).isLessThan(DAY_MS)
        assertThat(result.backtest.trades[2].timestamp).isGreaterThanOrEqualTo(DAY_MS)
        assertThat(result.backtest.positions).isEmpty()
    }

    @TestFactory
    fun `risk gates block reentry without blocking the first complete lifecycle across replay modes`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        gateCases().map { case ->
            DynamicTest.dynamicTest(case.id) {
                val strategyPath = writeStrategy(tempDir, "reentry_${case.id}")

                val result =
                    GeneratedStrategyReplay.assertTickBarAndLiveParity(
                        path = strategyPath,
                        closes = REENTRY_PRICES,
                        expectedTradeCount = 2,
                        expectedRejectionCount = 2,
                        expectedHaltCount = if (case.expectedHaltStrategyId == NO_HALT_EXPECTED) 0 else 1,
                        startingBalance = STARTING_BALANCE,
                        strategyRiskLimits = case.strategyRiskLimits,
                        dailyDdBasis = case.dailyDdBasis,
                        totalDdBasis = case.totalDdBasis,
                        haltRules = case.haltRules,
                    )

                assertThat(result.backtest).isEqualTo(result.live)
                assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL")
                assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "90")
                assertThat(result.backtest.rejections.map { it.side }).containsOnly("BUY")
                assertThat(result.backtest.rejections.map { it.reason }).allSatisfy { reason ->
                    assertThat(reason).contains(case.expectedReason)
                }
                assertThat(result.backtest.rejections.map { it.timestamp })
                    .allSatisfy { timestamp ->
                        assertThat(timestamp).isGreaterThan(result.backtest.trades.last().timestamp)
                    }
                if (case.expectedHaltStrategyId == NO_HALT_EXPECTED) {
                    assertThat(result.backtest.halts).isEmpty()
                } else {
                    assertThat(result.backtest.halts.single().reason).contains(case.expectedReason)
                    assertThat(result.backtest.halts.single().strategyId).isEqualTo(case.expectedHaltStrategyId)
                }
                assertThat(result.backtest.positions).isEmpty()
            }
        }

    private fun gateCases(): List<GateCase> =
        listOf(
            GateCase(
                id = "max_trades",
                expectedReason = "MaxTradesPerDay",
                strategyRiskLimits = StrategyRiskLimits(maxTradesPerDay = 1),
                expectedHaltStrategyId = NO_HALT_EXPECTED,
            ),
            GateCase(
                id = "cooldown_after_loss",
                expectedReason = "CooldownAfterLoss",
                strategyRiskLimits = StrategyRiskLimits(cooldownAfterLossMs = TEN_MINUTES_MS),
                expectedHaltStrategyId = NO_HALT_EXPECTED,
            ),
            GateCase(
                id = "loss_streak_halt",
                expectedReason = "LossStreakHalt",
                strategyRiskLimits = StrategyRiskLimits(lossStreakHalt = 1),
                expectedHaltStrategyId = "reentry_loss_streak_halt",
            ),
            GateCase(
                id = "strategy_daily_loss",
                expectedReason = "strategy daily loss",
                strategyRiskLimits = StrategyRiskLimits(maxDailyLoss = BigDecimal("5")),
                expectedHaltStrategyId = "reentry_strategy_daily_loss",
            ),
            GateCase(
                id = "strategy_drawdown",
                expectedReason = "strategy drawdown",
                strategyRiskLimits = StrategyRiskLimits(maxDrawdownPct = BigDecimal("0.005")),
                expectedHaltStrategyId = "reentry_strategy_drawdown",
            ),
            GateCase(
                id = "strategy_daily_drawdown",
                expectedReason = "strategy daily drawdown",
                strategyRiskLimits = StrategyRiskLimits(maxDailyDrawdownPct = BigDecimal("0.005")),
                dailyDdBasis = DailyDrawdownBasis.EQUITY,
                expectedHaltStrategyId = "reentry_strategy_daily_drawdown",
            ),
            GateCase(
                id = "global_daily_loss",
                expectedReason = "daily loss",
                haltRules = { HaltRules.standard(maxDailyLoss = BigDecimal("5")) },
            ),
            GateCase(
                id = "global_drawdown",
                expectedReason = "drawdown",
                haltRules = {
                    HaltRules.standard(
                        maxDailyLoss = BigDecimal.ZERO,
                        maxDrawdownPct = BigDecimal("0.005"),
                        totalDdBasis = DrawdownBasis.STATIC,
                        startingBalance = STARTING_BALANCE,
                    )
                },
            ),
            GateCase(
                id = "global_daily_drawdown",
                expectedReason = "daily drawdown",
                dailyDdBasis = DailyDrawdownBasis.EQUITY,
                haltRules = {
                    HaltRules.standard(
                        maxDailyLoss = BigDecimal.ZERO,
                        maxDailyDrawdownPct = BigDecimal("0.005"),
                        startingBalance = STARTING_BALANCE,
                    )
                },
            ),
        )

    private fun writeStrategy(
        tempDir: Path,
        id: String,
    ): Path {
        val strategyPath = tempDir.resolve("$id.qkt")
        Files.writeString(
            strategyPath,
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close >= 100 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close <= 90 AND POSITION.x != 0
              THEN CLOSE x
            """.trimIndent(),
        )
        return strategyPath
    }

    private fun writeTimedReentryStrategy(
        tempDir: Path,
        id: String,
    ): Path {
        val strategyPath = tempDir.resolve("$id.qkt")
        Files.writeString(
            strategyPath,
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 90 AND POSITION.x != 0
              THEN CLOSE x

              WHEN x.close = 110 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 120 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 80 AND POSITION.x != 0
              THEN CLOSE x
            """.trimIndent(),
        )
        return strategyPath
    }

    private fun writePendingReentryStrategy(
        tempDir: Path,
        id: String,
    ): Path {
        val strategyPath = tempDir.resolve("$id.qkt")
        Files.writeString(
            strategyPath,
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close >= 100 AND POSITION.x = 0 AND OPEN_ORDERS.x = 0
              THEN BUY x SIZING 1 ORDER_TYPE = LIMIT AT 95 TIF GTD NOW + 4m

              WHEN x.close >= 110 AND POSITION.x != 0
              THEN CLOSE x
            """.trimIndent(),
        )
        return strategyPath
    }

    private fun writeLossStreakResetStrategy(
        tempDir: Path,
        id: String,
    ): Path {
        val strategyPath = tempDir.resolve("$id.qkt")
        Files.writeString(
            strategyPath,
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 90 AND POSITION.x != 0
              THEN CLOSE x

              WHEN x.close = 120 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 140 AND POSITION.x != 0
              THEN CLOSE x

              WHEN x.close = 150 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 130 AND POSITION.x != 0
              THEN CLOSE x

              WHEN x.close = 160 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 170 AND POSITION.x != 0
              THEN CLOSE x
            """.trimIndent(),
        )
        return strategyPath
    }

    private fun writeDailyHaltResetStrategy(
        tempDir: Path,
        id: String,
    ): Path {
        val strategyPath = tempDir.resolve("$id.qkt")
        Files.writeString(
            strategyPath,
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 90 AND POSITION.x != 0
              THEN CLOSE x

              WHEN x.close = 110 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 120 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 140 AND POSITION.x != 0
              THEN CLOSE x
            """.trimIndent(),
        )
        return strategyPath
    }

    private fun dailyHaltResetCandles(): Map<String, List<Candle>> =
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
        )

    private fun candle(
        close: String,
        startTime: Long,
    ): Candle =
        Candle(
            symbol = "BACKTEST:X",
            open = BigDecimal(close),
            high = BigDecimal(close),
            low = BigDecimal(close),
            close = BigDecimal(close),
            volume = BigDecimal.ONE,
            startTime = startTime,
            endTime = startTime + ONE_MINUTE_MS,
        )

    private companion object {
        val STARTING_BALANCE: BigDecimal = BigDecimal("1000")
        val REENTRY_PRICES: List<String> = listOf("100", "101", "102", "90", "90", "105", "106", "90")
        const val TEN_MINUTES_MS: Long = 10 * 60 * 1000L
        const val NO_HALT_EXPECTED: String = "__none__"
        const val ONE_MINUTE_MS: Long = 60_000L
        const val DAY_MS: Long = 86_400_000L
    }
}
