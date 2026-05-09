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
