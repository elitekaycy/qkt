package com.qkt.parity

import com.qkt.cli.Config
import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.risk.HaltRules
import com.qkt.risk.StrategyRiskLimits
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class GeneratedRiskLifecycleParityTest {
    private data class Case(
        val id: String,
        val riskKey: String,
        val riskValue: String,
        val global: Boolean = false,
        val expectedReason: String,
    )

    private val cases =
        listOf(
            Case("max_trades", "max_trades_per_day", "1", expectedReason = "MaxTradesPerDay"),
            Case("loss_cooldown", "cooldown_after_loss", "10m", expectedReason = "CooldownAfterLoss"),
            Case("loss_streak", "loss_streak_halt", "1", expectedReason = "LossStreakHalt"),
            Case("strategy_daily_loss", "max_daily_loss", "5", expectedReason = "strategy daily loss"),
            Case("strategy_drawdown", "max_drawdown_pct", "5", expectedReason = "strategy drawdown"),
            Case(
                "strategy_daily_drawdown",
                "max_daily_drawdown_pct",
                "5",
                expectedReason = "strategy daily drawdown",
            ),
            Case("global_daily_loss", "max_daily_loss", "5", global = true, expectedReason = "daily loss"),
            Case("global_drawdown", "max_drawdown_pct", "5", global = true, expectedReason = "drawdown"),
            Case(
                "global_daily_drawdown",
                "max_daily_drawdown_pct",
                "5",
                global = true,
                expectedReason = "daily drawdown",
            ),
        )

    @TestFactory
    fun `loaded risk lifecycle controls match ticks bars backtest and live paper`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val strategyPath = tempDir.resolve("${case.id}.qkt")
                val source = strategySource(case.id)
                Files.writeString(strategyPath, source)
                val configPath = tempDir.resolve("${case.id}.yaml")
                Files.writeString(configPath, riskConfig(case))
                val config = Config.load(configPath)
                val limits = config.perStrategyRisk[case.id]?.toLimits() ?: StrategyRiskLimits()
                val haltRules = { globalHaltRules(config) }
                val expectedHaltCount = if (case.riskKey in PACER_REJECTION_KEYS) 0 else 1

                GeneratedStrategyReplay.assertTickAndBarParity(
                    path = strategyPath,
                    closes = PRICES,
                    expectedTradeCount = 2,
                    expectedRejectionCount = 1,
                    expectedHaltCount = expectedHaltCount,
                    startingBalance = STARTING_BALANCE,
                    strategyRiskLimits = limits,
                    dailyDdBasis = config.dailyDdBasis,
                    totalDdBasis = config.totalDdBasis,
                    haltRules = haltRules,
                )

                val result =
                    DslParityHarness.run(
                        strategyId = case.id,
                        source = source,
                        ticks = GeneratedTape.ticks(PRICES + PRICES.last()),
                        startingBalance = STARTING_BALANCE,
                        strategyRiskLimits = limits,
                        dailyDdBasis = config.dailyDdBasis,
                        totalDdBasis = config.totalDdBasis,
                        haltRules = haltRules,
                    )

                assertThat(result.live).isEqualTo(result.backtest)
                assertThat(result.backtest.trades.map { it.side }).containsExactly("BUY", "SELL")
                assertThat(result.backtest.pnl.realized).isEqualTo("-10")
                assertThat(result.backtest.rejections).hasSize(1)
                if (expectedHaltCount == 0) {
                    assertThat(
                        result.backtest.rejections
                            .single()
                            .reason,
                    ).contains(case.expectedReason)
                    assertThat(result.backtest.halts).isEmpty()
                } else {
                    assertThat(
                        result.backtest.rejections
                            .single()
                            .reason,
                    ).contains("halted")
                    assertThat(
                        result.backtest.halts
                            .single()
                            .reason,
                    ).contains(case.expectedReason)
                    assertThat(
                        result.backtest.halts
                            .single()
                            .strategyId,
                    ).isEqualTo(if (case.global) null else case.id)
                }
            }
        }

    private fun strategySource(id: String): String =
        """
        STRATEGY $id VERSION 1
        SYMBOLS x = BACKTEST:X EVERY 1m
        RULES
          WHEN x.close = 100 AND POSITION.x = 0
          THEN BUY x SIZING 1

          WHEN x.close = 90 AND POSITION.x != 0
          THEN CLOSE x

          WHEN x.close = 80 AND POSITION.x = 0
          THEN BUY x SIZING 1
        """.trimIndent()

    private fun riskConfig(case: Case): String =
        if (case.global) {
            """
            risk:
              ${case.riskKey}: "${case.riskValue}"
            """.trimIndent()
        } else {
            """
            risk:
              max_daily_loss: "0"
              per_strategy:
                ${case.id}:
                  ${case.riskKey}: "${case.riskValue}"
            """.trimIndent()
        }

    private fun globalHaltRules(config: Config) =
        HaltRules.standard(
            maxDailyLoss = config.maxDailyLoss,
            maxDrawdownPct = config.maxDrawdownPct,
            maxDailyDrawdownPct = config.maxDailyDrawdownPct,
            totalDdBasis = config.totalDdBasis,
            startingBalance = STARTING_BALANCE,
        )

    private object GeneratedTape {
        fun ticks(prices: List<String>) =
            prices.mapIndexed { index, price ->
                com.qkt.marketdata.Tick("BACKTEST:X", BigDecimal(price), index * 60_000L)
            }
    }

    private companion object {
        val STARTING_BALANCE: BigDecimal = BigDecimal("100")
        val PRICES: List<String> = listOf("100", "100", "90", "90", "80", "70")
        val PACER_REJECTION_KEYS: Set<String> = setOf("max_trades_per_day", "cooldown_after_loss")
    }
}
