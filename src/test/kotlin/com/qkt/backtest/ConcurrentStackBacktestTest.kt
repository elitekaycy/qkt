package com.qkt.backtest

import com.qkt.backtest.StackBacktestFixtures.tick
import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.execution.At
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.OrderRequest
import com.qkt.execution.StackPlan
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConcurrentStackBacktestTest {
    @Test
    fun `concurrent stacks one SL fire does not cancel other pending layers`() {
        // Stack A has a tight SL (BY 5) so it goes flat quickly; Stack B's pending layer 2 must
        // remain alive across A's flat-detection. If B's pending layer 2 gets cancelled when A
        // closes, layer 2 won't fire when its trigger price is reached later.
        val planA =
            StackPlan(
                layers =
                    listOf(
                        LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), Market, Immediate),
                        LayerSpec(
                            2,
                            SizeQty(NumLit(BigDecimal("0.1"))),
                            Market,
                            At(
                                BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("500"))),
                                StackDirection.ABOVE,
                            ),
                        ),
                    ),
                outerBracket = BracketAst(stopLoss = ChildBy(NumLit(BigDecimal("5")))),
            )
        val planB =
            StackPlan(
                layers =
                    listOf(
                        LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), Market, Immediate),
                        LayerSpec(
                            2,
                            SizeQty(NumLit(BigDecimal("0.1"))),
                            Market,
                            At(
                                BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("50"))),
                                StackDirection.ABOVE,
                            ),
                        ),
                    ),
            )

        val strategy =
            object : Strategy {
                private var submittedA = false
                private var submittedB = false

                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    if (tick.symbol != "btcusdt") return
                    if (!submittedA && tick.price >= BigDecimal("50000")) {
                        submittedA = true
                        emit(
                            Signal.Submit(
                                OrderRequest.Stack(
                                    id = "stkA",
                                    symbol = "btcusdt",
                                    side = Side.BUY,
                                    quantity = BigDecimal("0.2"),
                                    plan = planA,
                                    timeInForce = TimeInForce.GTC,
                                    timestamp = ctx.clock.now(),
                                ),
                            ),
                        )
                        return
                    }
                    if (submittedA && !submittedB && tick.price >= BigDecimal("50010")) {
                        submittedB = true
                        emit(
                            Signal.Submit(
                                OrderRequest.Stack(
                                    id = "stkB",
                                    symbol = "btcusdt",
                                    side = Side.BUY,
                                    quantity = BigDecimal("0.2"),
                                    plan = planB,
                                    timeInForce = TimeInForce.GTC,
                                    timestamp = ctx.clock.now(),
                                ),
                            ),
                        )
                    }
                }
            }

        val ticks =
            listOf(
                tick("50000", 1L), // A submits, A's layer 1 fills @ 50000; A's SL at 49995
                tick("50010", 2L), // B submits, B's layer 1 fills @ 50010
                tick("49994", 3L), // A's SL fires (A goes flat → A's pending l2 cancels via flat detection)
                tick("50060", 4L), // B's layer 2 trigger (50010 + 50) hits
            )

        val result =
            Backtest(
                strategies = listOf("stack-concurrent" to strategy),
                ticks = ticks,
            ).run()

        val buys = result.trades.filter { it.trade.side == Side.BUY }
        val sells = result.trades.filter { it.trade.side == Side.SELL }
        // 3 buys: A-l1 @ 50000, B-l1 @ 50010, B-l2 @ 50060. (A-l2 cancelled by A's SL.)
        assertThat(buys).hasSize(3)
        // 1 sell: A's SL @ 49995.
        assertThat(sells).hasSize(1)
        // Final position is B's 0.2 only (A is flat).
        assertThat(result.finalPositions["btcusdt"]?.quantity)
            .`as`("stack B's pending layer 2 should fire even after stack A goes flat")
            .isNotNull
            .isEqualByComparingTo(BigDecimal("0.2"))
    }
}
