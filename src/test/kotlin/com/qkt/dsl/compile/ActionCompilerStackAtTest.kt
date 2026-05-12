package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackAtClause
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ActionCompilerStackAtTest {
    private fun makeCtx(): EvalContext {
        val close = BigDecimal("1.1000")
        val candle =
            Candle(
                "EURUSD",
                close,
                close,
                close,
                close,
                BigDecimal.ZERO,
                0L,
                1L,
            )
        return EvalContext(
            candle = candle,
            streams = mapOf("eur" to HubKey("BACKTEST", "EURUSD", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    }

    private fun stackAtClause(
        threshold: String = "0.005",
        withinMs: Long = 30 * 60 * 1000L,
        sizeQty: String = "0.05",
        sl: String = "0.005",
        tp: String = "0.020",
    ) = StackAtClause(
        mfeThreshold = NumLit(BigDecimal(threshold)),
        withinDuration = DurationAst(withinMs),
        sizing = SizeQty(NumLit(BigDecimal(sizeQty))),
        bracket =
            BracketAst(
                stopLoss = ChildBy(NumLit(BigDecimal(sl))),
                takeProfit = ChildBy(NumLit(BigDecimal(tp))),
            ),
    )

    @Test
    fun `BUY with STACK_AT registers a PendingStack on emit`() {
        val pending = PendingStacks()
        val action =
            Buy(
                stream = "eur",
                opts =
                    ActionOpts(
                        sizing = SizeQty(NumLit(BigDecimal("0.1"))),
                        stackAts = listOf(stackAtClause(), stackAtClause(threshold = "0.010")),
                    ),
            )
        val compiled = ActionCompiler(ExprCompiler(), pendingStacks = pending).compile(action)
        val sigs = compiled.invoke(makeCtx())

        assertThat(sigs).hasSize(1)
        val req = (sigs.single() as Signal.Submit).request
        // Plain BUY with no bracket/oco compiles to a Market submit
        assertThat(req).isInstanceOf(OrderRequest.Market::class.java)

        assertThat(pending.size()).isEqualTo(1)
        val entry = pending.consume(req.id)!!
        assertThat(entry.symbol).isEqualTo("EURUSD")
        assertThat(entry.side).isEqualTo(Side.BUY)
        assertThat(entry.tiers).hasSize(2)
        assertThat(entry.tiers[0].mfeThreshold).isEqualByComparingTo("0.005")
        assertThat(entry.tiers[1].mfeThreshold).isEqualByComparingTo("0.010")
    }

    @Test
    fun `SELL with STACK_AT registers under SELL side`() {
        val pending = PendingStacks()
        val action =
            Sell(
                stream = "eur",
                opts =
                    ActionOpts(
                        sizing = SizeQty(NumLit(BigDecimal("0.1"))),
                        stackAts = listOf(stackAtClause()),
                    ),
            )
        val sigs = ActionCompiler(ExprCompiler(), pendingStacks = pending).compile(action).invoke(makeCtx())
        val req = (sigs.single() as Signal.Submit).request
        val entry = pending.consume(req.id)!!
        assertThat(entry.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `BRACKET parent with STACK_AT registers under the inner entry id`() {
        val pending = PendingStacks()
        val action =
            Buy(
                stream = "eur",
                opts =
                    ActionOpts(
                        sizing = SizeQty(NumLit(BigDecimal("0.1"))),
                        bracket =
                            BracketAst(
                                stopLoss = ChildBy(NumLit(BigDecimal("0.005"))),
                                takeProfit = ChildBy(NumLit(BigDecimal("0.020"))),
                            ),
                        stackAts = listOf(stackAtClause()),
                    ),
            )
        val compiled = ActionCompiler(ExprCompiler(), pendingStacks = pending).compile(action)
        val sigs = compiled.invoke(makeCtx())

        val bracket = (sigs.single() as Signal.Submit).request as OrderRequest.Bracket
        // Pending must be keyed on the entry id, not the bracket id — brokers fill the inner market
        assertThat(pending.contains(bracket.entry.id)).isTrue
        assertThat(pending.contains(bracket.id)).isFalse
    }

    @Test
    fun `BUY without STACK_AT does not touch the PendingStacks registry`() {
        val pending = PendingStacks()
        val action =
            Buy(
                stream = "eur",
                opts = ActionOpts(sizing = SizeQty(NumLit(BigDecimal("0.1")))),
            )
        ActionCompiler(ExprCompiler(), pendingStacks = pending).compile(action).invoke(makeCtx())
        assertThat(pending.size()).isEqualTo(0)
    }

    @Test
    fun `null PendingStacks is tolerated when stackAts are present`() {
        val action =
            Buy(
                stream = "eur",
                opts =
                    ActionOpts(
                        sizing = SizeQty(NumLit(BigDecimal("0.1"))),
                        stackAts = listOf(stackAtClause()),
                    ),
            )
        // Must not throw — null registry just means no runtime stack tracking
        val sigs = ActionCompiler(ExprCompiler(), pendingStacks = null).compile(action).invoke(makeCtx())
        assertThat(sigs).hasSize(1)
    }
}
