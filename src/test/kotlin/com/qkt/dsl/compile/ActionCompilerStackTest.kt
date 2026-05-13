package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
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
                "BACKTEST:BTCUSDT",
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

    @Test
    fun `STACK SPACING with literal SIZING qty produces resolved layers`() {
        val action =
            Buy(
                stream = "btc",
                opts =
                    ActionOpts(
                        sizing = SizeQty(NumLit(BigDecimal("0.5"))),
                        stack = StackSpacing(3, NumLit(BigDecimal("100")), StackDirection.TRADE_DIRECTION),
                    ),
            )
        val sigs = ActionCompiler(ExprCompiler()).compile(action).invoke(makeCtx())
        val req = (sigs.single() as Signal.Submit).request as OrderRequest.Stack

        assertThat(req.plan.layers).hasSize(3)
        for (layer in req.plan.layers) {
            assertThat(layer.resolvedQuantity)
                .describedAs("layer ${layer.index} resolvedQuantity")
                .isNotNull
                .isEqualByComparingTo(BigDecimal("0.5"))
        }
        assertThat(req.quantity).isEqualByComparingTo(BigDecimal("1.5"))
    }

    @Test
    fun `STACK layer-list form produces explicit per-layer quantities`() {
        val action =
            Buy(
                stream = "btc",
                opts =
                    ActionOpts(
                        stack =
                            StackLayers(
                                listOf(
                                    StackLayer(SizeQty(NumLit(BigDecimal("0.1")))),
                                    StackLayer(
                                        SizeQty(NumLit(BigDecimal("0.2"))),
                                        at = BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("100"))),
                                    ),
                                    StackLayer(
                                        SizeQty(NumLit(BigDecimal("0.3"))),
                                        at = BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("200"))),
                                    ),
                                ),
                            ),
                    ),
            )
        val sigs = ActionCompiler(ExprCompiler()).compile(action).invoke(makeCtx())
        val req = (sigs.single() as Signal.Submit).request as OrderRequest.Stack

        val resolved = req.plan.layers.map { it.resolvedQuantity!! }
        assertThat(resolved[0]).isEqualByComparingTo(BigDecimal("0.1"))
        assertThat(resolved[1]).isEqualByComparingTo(BigDecimal("0.2"))
        assertThat(resolved[2]).isEqualByComparingTo(BigDecimal("0.3"))
        assertThat(req.quantity).isEqualByComparingTo(BigDecimal("0.6"))
    }
}
