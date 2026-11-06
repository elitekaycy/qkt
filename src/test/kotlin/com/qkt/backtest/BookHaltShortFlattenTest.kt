package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.marketdata.Tick
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.HaltRules
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An account-wide halt in a portfolio book closes short legs with buys, as it closes long legs with sells. */
class BookHaltShortFlattenTest {
    private fun sellFirstTick(): Strategy =
        object : Strategy {
            private var sold = false

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (!sold) {
                    sold = true
                    emit(Signal.Sell("XAUUSD", Money.of("1")))
                }
            }
        }

    @Test
    fun `a portfolio drawdown breach buys back every child's short leg`() {
        val result =
            Backtest(
                strategies = listOf("alpha" to sellFirstTick(), "beta" to sellFirstTick()),
                ticks = listOf(100L to 1L, 120L to 2L, 130L to 3L).map { (p, t) -> Tick("XAUUSD", Money.of("$p"), t) },
                startingBalance = BigDecimal("100"),
                startingBalances = mapOf("alpha" to BigDecimal("50"), "beta" to BigDecimal("50")),
                bookCapital = BigDecimal("100"),
                dailyDdBasis = DailyDrawdownBasis.EQUITY,
                haltRules =
                    HaltRules.standard(
                        maxDailyLoss = BigDecimal.ZERO,
                        maxDailyDrawdownPct = BigDecimal("0.10"),
                    ),
            ).run()

        assertThat(result.halts).hasSize(1)
        assertThat(result.finalPositions).isEmpty()
        assertThat(result.trades.filter { it.reducedExposure })
            .hasSize(2)
            .allSatisfy { record ->
                assertThat(record.trade.side).isEqualTo(Side.BUY)
                assertThat(record.trade.timestamp).isEqualTo(2L)
            }
    }
}
