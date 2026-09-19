package com.qkt.parity

import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.parity.ReentryReplayFixtures.NO_HALT_EXPECTED
import com.qkt.parity.ReentryReplayFixtures.REENTRY_PRICES
import com.qkt.parity.ReentryReplayFixtures.STARTING_BALANCE
import com.qkt.parity.ReentryReplayFixtures.TEN_MINUTES_MS
import com.qkt.parity.ReentryReplayFixtures.withQuietParityLogs
import com.qkt.parity.ReentryStrategyFiles.writeStrategy
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.DrawdownBasis
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltRules
import com.qkt.risk.StrategyRiskLimits
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class ReentryRiskGateParityTest {
    private data class GateCase(
        val id: String,
        val expectedReason: String,
        val strategyRiskLimits: StrategyRiskLimits = StrategyRiskLimits(),
        val dailyDdBasis: DailyDrawdownBasis = DailyDrawdownBasis.BALANCE,
        val totalDdBasis: DrawdownBasis = DrawdownBasis.STATIC,
        val haltRules: () -> List<HaltRule> = { emptyList() },
        val expectedHaltStrategyId: String? = null,
    )

    @TestFactory
    fun `risk gates block reentry without blocking the first complete lifecycle across replay modes`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        gateCases().map { case ->
            DynamicTest.dynamicTest(case.id) {
                val strategyPath = writeStrategy(tempDir, "reentry_${case.id}")

                val result =
                    withQuietParityLogs {
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
                    }

                assertThat(result.backtest).isEqualTo(result.live)
                assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL")
                assertThat(result.backtest.trades.map { it.price }).containsExactly("101", "90")
                assertThat(result.backtest.rejections.map { it.side }).containsOnly("BUY")
                assertThat(result.backtest.rejections.map { it.reason }).allSatisfy { reason ->
                    assertThat(reason).contains(case.expectedReason)
                }
                assertThat(result.backtest.rejections.map { it.timestamp })
                    .allSatisfy { timestamp ->
                        assertThat(timestamp)
                            .isGreaterThan(
                                result.backtest.trades
                                    .last()
                                    .timestamp,
                            )
                    }
                if (case.expectedHaltStrategyId == NO_HALT_EXPECTED) {
                    assertThat(result.backtest.halts).isEmpty()
                } else {
                    assertThat(
                        result.backtest.halts
                            .single()
                            .reason,
                    ).contains(case.expectedReason)
                    assertThat(
                        result.backtest.halts
                            .single()
                            .strategyId,
                    ).isEqualTo(case.expectedHaltStrategyId)
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
}
