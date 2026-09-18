package com.qkt.backtest

import com.qkt.backtest.StackBacktestFixtures.onceStrategy
import com.qkt.backtest.StackBacktestFixtures.threeLayerPlan
import com.qkt.backtest.StackBacktestFixtures.tick
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StackCancellationBacktestTest {
    @Test
    fun `SL fires on layer 1 and pending layers are cancelled`() {
        val plan = threeLayerPlan(bracket = BracketAst(stopLoss = ChildBy(NumLit(BigDecimal("50")))))
        val ticks =
            listOf(
                tick("49500", 1L),
                tick("50000", 2L),
                tick("49950", 3L),
            )

        val result =
            Backtest(
                strategies = listOf("stack-sl" to onceStrategy("btcusdt", plan)),
                ticks = ticks,
            ).run()

        val buys = result.trades.filter { it.trade.side == Side.BUY }
        val sells = result.trades.filter { it.trade.side == Side.SELL }

        assertThat(buys).hasSize(1)
        assertThat(buys[0].trade.price).isEqualByComparingTo(BigDecimal("50000"))

        assertThat(sells).hasSize(1)
        assertThat(sells[0].trade.price).isEqualByComparingTo(BigDecimal("49950"))

        assertThat(result.finalPositions["btcusdt"]).isNull()
    }

    @Test
    fun `CLOSE signal cancels pending stack layers so they do not re-enter`() {
        val plan = threeLayerPlan()
        // Layer 1 fills at 50000; CLOSE fires on the next tick before layers 2 and 3 trigger.
        var closeEmitted = false
        val strategy =
            object : com.qkt.strategy.Strategy {
                private var submitted = false

                override fun onTick(
                    tick: com.qkt.marketdata.Tick,
                    ctx: com.qkt.strategy.StrategyContext,
                    emit: (com.qkt.strategy.Signal) -> Unit,
                ) {
                    if (tick.symbol != "btcusdt") return
                    if (!submitted && tick.price >= Money.of("50000")) {
                        submitted = true
                        emit(
                            com.qkt.strategy.Signal.Submit(
                                com.qkt.execution.OrderRequest.Stack(
                                    id = "stk-close-test",
                                    symbol = "btcusdt",
                                    side = com.qkt.common.Side.BUY,
                                    quantity = BigDecimal("0.3"),
                                    plan = plan,
                                    timeInForce = com.qkt.execution.TimeInForce.GTC,
                                    timestamp = ctx.clock.now(),
                                ),
                            ),
                        )
                        return
                    }
                    // On the next tick after submission, emit CLOSE to cancel pending layers.
                    if (submitted && !closeEmitted) {
                        closeEmitted = true
                        val pos = ctx.positions.positionFor("btcusdt") ?: return
                        val qty = pos.quantity
                        emit(
                            com.qkt.strategy.Signal
                                .CancelPendingForSymbol("btcusdt"),
                        )
                        if (qty.signum() > 0) {
                            emit(
                                com.qkt.strategy.Signal
                                    .Sell("btcusdt", qty),
                            )
                        }
                    }
                }
            }

        val ticks =
            listOf(
                tick("49500", 1L),
                tick("50000", 2L),
                // Tick 3: CLOSE fires, cancels pending layers 2 and 3.
                tick("50010", 3L),
                // Ticks 4+ would trigger layers 2 and 3 if they weren't cancelled.
                tick("50100", 4L),
                tick("50200", 5L),
                tick("50250", 6L),
            )

        val result =
            Backtest(
                strategies = listOf("stack-close-e2e" to strategy),
                ticks = ticks,
            ).run()

        // Only layer 1 should have filled, then CLOSE sold it. No re-entry.
        val buys = result.trades.filter { it.trade.side == com.qkt.common.Side.BUY }
        val sells = result.trades.filter { it.trade.side == com.qkt.common.Side.SELL }
        assertThat(buys).hasSize(1)
        assertThat(sells).hasSize(1)
        assertThat(result.finalPositions["btcusdt"]).isNull()
    }

    @Test
    fun `WITHIN expiry cancels pending layers after deadline passes`() {
        val oneHourMs = 3_600_000L
        val plan = threeLayerPlan(withinMillis = oneHourMs)
        val ticks =
            listOf(
                tick("50000", 0L),
                tick("50000", oneHourMs + 1L),
            )

        val result =
            Backtest(
                strategies = listOf("stack-within" to onceStrategy("btcusdt", plan)),
                ticks = ticks,
            ).run()

        val buys = result.trades.filter { it.trade.side == Side.BUY }
        assertThat(buys).hasSize(1)
        assertThat(buys[0].trade.price).isEqualByComparingTo(BigDecimal("50000"))

        assertThat(result.finalPositions["btcusdt"]?.quantity)
            .isNotNull
            .isEqualByComparingTo(BigDecimal("0.1"))
    }
}
