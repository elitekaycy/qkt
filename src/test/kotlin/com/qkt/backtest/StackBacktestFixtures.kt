package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
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

/** Ticks, three-layer stack plans and one-shot stack strategies for the stack backtest tests. */
internal object StackBacktestFixtures {
    fun tick(
        price: String,
        ts: Long,
    ) = Tick("btcusdt", Money.of(price), ts)

    fun threeLayerPlan(
        bracket: BracketAst? = null,
        withinMillis: Long? = null,
    ): StackPlan =
        StackPlan(
            layers =
                listOf(
                    LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), Market, Immediate),
                    LayerSpec(
                        2,
                        SizeQty(NumLit(BigDecimal("0.1"))),
                        Market,
                        At(
                            BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("100"))),
                            StackDirection.TRADE_DIRECTION,
                        ),
                    ),
                    LayerSpec(
                        3,
                        SizeQty(NumLit(BigDecimal("0.1"))),
                        Market,
                        At(
                            BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("200"))),
                            StackDirection.TRADE_DIRECTION,
                        ),
                    ),
                ),
            outerBracket = bracket,
            withinMillis = withinMillis,
        )

    fun onceStrategy(
        symbol: String,
        plan: StackPlan,
    ): Strategy {
        var submitted = false
        return object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (submitted) return
                if (tick.symbol != symbol) return
                if (tick.price < BigDecimal("50000")) return
                submitted = true
                emit(
                    Signal.Submit(
                        OrderRequest.Stack(
                            id = "stk-test",
                            symbol = symbol,
                            side = Side.BUY,
                            quantity = BigDecimal("0.3"),
                            plan = plan,
                            timeInForce = TimeInForce.GTC,
                            timestamp = ctx.clock.now(),
                        ),
                    ),
                )
            }
        }
    }

    fun threeLayerSellPlan(): StackPlan =
        StackPlan(
            layers =
                listOf(
                    LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), Market, Immediate),
                    LayerSpec(
                        2,
                        SizeQty(NumLit(BigDecimal("0.1"))),
                        Market,
                        At(
                            BinaryOp(BinOp.SUB, StackEntryRef, NumLit(BigDecimal("100"))),
                            StackDirection.BELOW,
                        ),
                    ),
                    LayerSpec(
                        3,
                        SizeQty(NumLit(BigDecimal("0.1"))),
                        Market,
                        At(
                            BinaryOp(BinOp.SUB, StackEntryRef, NumLit(BigDecimal("200"))),
                            StackDirection.BELOW,
                        ),
                    ),
                ),
        )

    fun onceSellStrategy(plan: StackPlan): Strategy {
        var submitted = false
        return object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (submitted) return
                if (tick.symbol != "btcusdt") return
                if (tick.price > BigDecimal("50000")) return
                submitted = true
                emit(
                    Signal.Submit(
                        OrderRequest.Stack(
                            id = "stk-sell",
                            symbol = "btcusdt",
                            side = Side.SELL,
                            quantity = BigDecimal("0.3"),
                            plan = plan,
                            timeInForce = TimeInForce.GTC,
                            timestamp = ctx.clock.now(),
                        ),
                    ),
                )
            }
        }
    }
}
