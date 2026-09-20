package com.qkt.backtest

import com.qkt.backtest.StackBacktestFixtures.onceSellStrategy
import com.qkt.backtest.StackBacktestFixtures.onceStrategy
import com.qkt.backtest.StackBacktestFixtures.threeLayerPlan
import com.qkt.backtest.StackBacktestFixtures.threeLayerSellPlan
import com.qkt.backtest.StackBacktestFixtures.tick
import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.execution.At
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.StackPlan
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StackPyramidBacktestTest {
    @Test
    fun `pyramid happy path fills three layers at entry plus spacing`() {
        val plan = threeLayerPlan()
        val ticks =
            listOf(
                tick("49500", 1L),
                tick("50000", 2L),
                tick("50100", 3L),
                tick("50200", 4L),
                tick("50250", 5L),
            )

        val result =
            Backtest(
                strategies = listOf("stack-e2e" to onceStrategy("btcusdt", plan)),
                ticks = ticks,
            ).run()

        val buys = result.trades.filter { it.trade.side == Side.BUY }
        assertThat(buys).hasSize(3)

        val prices = buys.map { it.trade.price }.sortedBy { it }
        assertThat(prices[0]).isEqualByComparingTo(BigDecimal("50000"))
        assertThat(prices[1]).isEqualByComparingTo(BigDecimal("50100"))
        assertThat(prices[2]).isEqualByComparingTo(BigDecimal("50200"))

        assertThat(result.finalPositions["btcusdt"]?.quantity)
            .isNotNull
            .isEqualByComparingTo(BigDecimal("0.3"))
    }

    @Test
    fun `SELL stack pyramid fills three layers at decreasing prices`() {
        val plan = threeLayerSellPlan()
        val ticks =
            listOf(
                tick("50500", 1L),
                tick("50000", 2L),
                tick("49900", 3L),
                tick("49800", 4L),
                tick("49750", 5L),
            )

        val result =
            Backtest(
                strategies = listOf("stack-sell-e2e" to onceSellStrategy(plan)),
                ticks = ticks,
            ).run()

        val sells = result.trades.filter { it.trade.side == Side.SELL }
        assertThat(sells).hasSize(3)
        val prices = sells.map { it.trade.price }.sortedByDescending { it }
        assertThat(prices[0]).isEqualByComparingTo(BigDecimal("50000"))
        assertThat(prices[1]).isEqualByComparingTo(BigDecimal("49900"))
        assertThat(prices[2]).isEqualByComparingTo(BigDecimal("49800"))

        assertThat(result.finalPositions["btcusdt"]?.quantity)
            .isNotNull
            .isEqualByComparingTo(BigDecimal("-0.3"))
    }

    @Test
    fun `BUY stack with BELOW direction averages down at decreasing prices`() {
        // BUY side but BELOW direction → layers must be LIMIT orders so they fill when price
        // falls TO or below the trigger. (A BUY Stop would fire when price rose THROUGH the
        // trigger, which is the wrong semantic for averaging-down.)
        val l2Trigger = BinaryOp(BinOp.SUB, StackEntryRef, NumLit(BigDecimal("100")))
        val l3Trigger = BinaryOp(BinOp.SUB, StackEntryRef, NumLit(BigDecimal("200")))
        val plan =
            StackPlan(
                layers =
                    listOf(
                        LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), Market, Immediate),
                        LayerSpec(
                            2,
                            SizeQty(NumLit(BigDecimal("0.1"))),
                            com.qkt.dsl.ast
                                .Limit(l2Trigger),
                            At(l2Trigger, StackDirection.BELOW),
                        ),
                        LayerSpec(
                            3,
                            SizeQty(NumLit(BigDecimal("0.1"))),
                            com.qkt.dsl.ast
                                .Limit(l3Trigger),
                            At(l3Trigger, StackDirection.BELOW),
                        ),
                    ),
            )
        val ticks =
            listOf(
                tick("49500", 1L),
                tick("50000", 2L),
                tick("49900", 3L),
                tick("49800", 4L),
                tick("49750", 5L),
            )

        val result =
            Backtest(
                strategies = listOf("stack-buy-below" to onceStrategy("btcusdt", plan)),
                ticks = ticks,
            ).run()

        val buys = result.trades.filter { it.trade.side == Side.BUY }
        assertThat(buys).hasSize(3)
        val prices = buys.map { it.trade.price }.sortedByDescending { it }
        assertThat(prices[0]).isEqualByComparingTo(BigDecimal("50000"))
        assertThat(prices[1]).isEqualByComparingTo(BigDecimal("49900"))
        assertThat(prices[2]).isEqualByComparingTo(BigDecimal("49800"))

        assertThat(result.finalPositions["btcusdt"]?.quantity)
            .isNotNull
            .isEqualByComparingTo(BigDecimal("0.3"))
    }
}
