package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.HaltRules
import com.qkt.risk.StrategyRiskLimits
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestRiskParityTest {
    private val symbol = "XAUUSD"

    @Test
    fun `per-strategy position size is enforced by replay wiring`() {
        val result =
            Backtest(
                strategies = listOf("alpha" to buyEveryTick()),
                ticks = listOf(tick("100", 1L), tick("101", 2L)),
                strategyRiskLimits =
                    mapOf(
                        "alpha" to StrategyRiskLimits(maxPositionSize = BigDecimal.ONE),
                    ),
            ).run()

        assertThat(result.trades).hasSize(1)
        assertThat(result.rejections).hasSize(1)
        assertThat(result.rejections.single().reason).contains("MaxStrategyPositionSize[alpha]")
    }

    @Test
    fun `global daily drawdown honors balance and equity bases`() {
        val balance = dailyDrawdownRun(DailyDrawdownBasis.BALANCE, perStrategy = false)
        val equity = dailyDrawdownRun(DailyDrawdownBasis.EQUITY, perStrategy = false)

        assertThat(balance.halts).isEmpty()
        assertThat(equity.halts).hasSize(1)
        assertThat(equity.halts.single().strategyId).isNull()
        assertThat(equity.halts.single().reason).contains("daily drawdown")
    }

    @Test
    fun `per-strategy daily drawdown honors balance and equity bases`() {
        val balance = dailyDrawdownRun(DailyDrawdownBasis.BALANCE, perStrategy = true)
        val equity = dailyDrawdownRun(DailyDrawdownBasis.EQUITY, perStrategy = true)

        assertThat(balance.halts).isEmpty()
        assertThat(equity.halts).hasSize(1)
        assertThat(equity.halts.single().strategyId).isEqualTo("alpha")
        assertThat(equity.halts.single().reason).contains("strategy daily drawdown")
    }

    private fun dailyDrawdownRun(
        basis: DailyDrawdownBasis,
        perStrategy: Boolean,
    ): BacktestResult {
        val nextDay = 86_400_000L
        val limit = BigDecimal("0.05")
        return Backtest(
            strategies = listOf("alpha" to buyFirstTick()),
            ticks =
                listOf(
                    tick("100", 1_000L),
                    tick("120", nextDay + 1_000L),
                    tick("110", nextDay + 2_000L),
                ),
            startingBalance = BigDecimal("100"),
            dailyDdBasis = basis,
            haltRules =
                if (perStrategy) {
                    emptyList()
                } else {
                    HaltRules.standard(
                        maxDailyLoss = BigDecimal.ZERO,
                        maxDailyDrawdownPct = limit,
                    )
                },
            strategyRiskLimits =
                if (perStrategy) {
                    mapOf("alpha" to StrategyRiskLimits(maxDailyDrawdownPct = limit))
                } else {
                    emptyMap()
                },
        ).run()
    }

    private fun buyEveryTick(): Strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                emit(Signal.Buy(symbol, Money.of("1")))
            }
        }

    private fun buyFirstTick(): Strategy =
        object : Strategy {
            private var bought = false

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (!bought) {
                    bought = true
                    emit(Signal.Buy(symbol, Money.of("1")))
                }
            }
        }

    private fun tick(
        price: String,
        timestamp: Long,
    ) = Tick(symbol, Money.of(price), timestamp)
}
