package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackSpacing
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.pnl.StrategyPnLView
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ActionCompilerStackTest {
    // Entry proxy for layer 1 is candle.close (1000).
    // Layers 2 and 3 are entry + 100 and entry + 200.
    // With RISK 0.01, equity 10000, SL BY 50: qty = 0.01 * 10000 / 50 = 2.0 per layer.
    private val entryPrice = BigDecimal("1000")
    private val equity = BigDecimal("10000")
    private val stopDistance = BigDecimal("50")
    private val expectedQtyPerLayer = BigDecimal("2.0000000000")

    private fun makeCtx(): EvalContext {
        val candle =
            Candle(
                "BTCUSDT",
                entryPrice,
                entryPrice,
                entryPrice,
                entryPrice,
                BigDecimal.ZERO,
                0L,
                1L,
            )
        val pnl =
            object : StrategyPnLView {
                override fun realized(): BigDecimal = BigDecimal.ZERO

                override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

                override fun total(): BigDecimal = BigDecimal.ZERO

                override fun equity(): BigDecimal = this@ActionCompilerStackTest.equity

                override fun balance(): BigDecimal = this@ActionCompilerStackTest.equity
            }
        return EvalContext(
            candle = candle,
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(pnl = pnl),
        )
    }

    @Test
    fun `STACK with RISK sizing resolves per-layer quantity at action-execute time`() {
        val action =
            Buy(
                stream = "btc",
                opts =
                    ActionOpts(
                        sizing = SizeRiskFrac(NumLit(BigDecimal("0.01"))),
                        stack = StackSpacing(3, NumLit(stopDistance), StackDirection.TRADE_DIRECTION),
                        bracket = BracketAst(stopLoss = ChildBy(NumLit(stopDistance))),
                    ),
            )
        val sigs = ActionCompiler(ExprCompiler()).compile(action).invoke(makeCtx())

        assertThat(sigs).hasSize(1)
        val sig = sigs[0]
        assertThat(sig).isInstanceOf(Signal.Submit::class.java)
        val req = (sig as Signal.Submit).request
        assertThat(req).isInstanceOf(OrderRequest.Stack::class.java)
        val stack = req as OrderRequest.Stack

        assertThat(stack.plan.layers).hasSize(3)
        for (layer in stack.plan.layers) {
            assertThat(layer.resolvedQuantity)
                .describedAs("layer ${layer.index} resolvedQuantity")
                .isNotNull
                .isEqualByComparingTo(expectedQtyPerLayer)
        }
    }

    @Test
    fun `STACK total quantity equals sum of per-layer resolved quantities`() {
        val action =
            Buy(
                stream = "btc",
                opts =
                    ActionOpts(
                        sizing = SizeRiskFrac(NumLit(BigDecimal("0.01"))),
                        stack = StackSpacing(3, NumLit(stopDistance), StackDirection.TRADE_DIRECTION),
                        bracket = BracketAst(stopLoss = ChildBy(NumLit(stopDistance))),
                    ),
            )
        val sig = ActionCompiler(ExprCompiler()).compile(action).invoke(makeCtx())[0] as Signal.Submit
        val stack = sig.request as OrderRequest.Stack

        val expectedTotal = expectedQtyPerLayer.multiply(BigDecimal("3"))
        assertThat(stack.quantity).isEqualByComparingTo(expectedTotal)
    }
}
