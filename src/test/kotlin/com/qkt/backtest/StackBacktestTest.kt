package com.qkt.backtest

import com.qkt.common.Money
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

class StackBacktestTest {
    private fun tick(
        price: String,
        ts: Long,
    ) = Tick("btcusdt", Money.of(price), ts)

    private fun threeLayerPlan(
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

    private fun onceStrategy(
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

    private fun threeLayerSellPlan(): StackPlan =
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

    private fun onceSellStrategy(plan: StackPlan): Strategy {
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
