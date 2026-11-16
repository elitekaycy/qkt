package com.qkt.backtest

import com.qkt.backtest.StackBacktestFixtures.onceStrategy
import com.qkt.backtest.StackBacktestFixtures.threeLayerPlan
import com.qkt.backtest.StackBacktestFixtures.tick
import com.qkt.common.Side
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StackEntryRiskReportTest {
    private fun by(points: String) = ChildBy(NumLit(BigDecimal(points)))

    @Test
    fun `every STACK entry reports the stop, target and risk its layer's bracket armed`() {
        val plan = threeLayerPlan(bracket = BracketAst(stopLoss = by("50"), takeProfit = by("300")))
        val ticks = listOf("49500", "50000", "50100", "50200", "50250").mapIndexed { i, p -> tick(p, i + 1L) }

        val result = Backtest(strategies = listOf("stack-risk" to onceStrategy("btcusdt", plan)), ticks = ticks).run()

        val entries = result.trades.filter { it.trade.side == Side.BUY }.sortedBy { it.trade.price }
        val prices =
            entries.map {
                it.trade.price
                    .stripTrailingZeros()
                    .toPlainString()
            }
        assertThat(prices).containsExactly("50000", "50100", "50200")
        entries.forEach { e ->
            assertThat(e.stopLossPrice).isEqualByComparingTo(e.trade.price - BigDecimal("50"))
            assertThat(e.takeProfitPrice).isEqualByComparingTo(e.trade.price + BigDecimal("300"))
            assertThat(e.riskUsd).isEqualByComparingTo(BigDecimal("5"))
        }
    }

    @Test
    fun `a STACK without a bracket reports no levels`() {
        val ticks = listOf("49500", "50000", "50100").mapIndexed { i, p -> tick(p, i + 1L) }

        val strategy = onceStrategy("btcusdt", threeLayerPlan())
        val result = Backtest(strategies = listOf("stack-bare" to strategy), ticks = ticks).run()

        val entries = result.trades.filter { it.trade.side == Side.BUY }
        assertThat(entries).isNotEmpty
        assertThat(entries.map { it.stopLossPrice }).containsOnlyNulls()
        assertThat(entries.map { it.riskUsd }).containsOnlyNulls()
    }
}
