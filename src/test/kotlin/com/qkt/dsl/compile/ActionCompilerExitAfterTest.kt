package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackSpacing
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.entryFillId
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ActionCompilerExitAfterTest {
    private val ctx =
        EvalContext(
            candle =
                Candle(
                    "BACKTEST:BTCUSDT",
                    BigDecimal("100"),
                    BigDecimal("100"),
                    BigDecimal("100"),
                    BigDecimal("100"),
                    BigDecimal.ZERO,
                    0L,
                    1L,
                ),
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    private val bracket =
        BracketAst(stopLoss = ChildBy(NumLit(BigDecimal("15"))), takeProfit = ChildBy(NumLit(BigDecimal("1"))))

    private fun opts(
        bracket: BracketAst? = null,
        stackAts: List<StackAtClause> = emptyList(),
    ) = ActionOpts(
        sizing = SizeQty(NumLit(BigDecimal("0.01"))),
        bracket = bracket,
        stackAts = stackAts,
        exitAfter = DurationAst(90_000L),
    )

    private fun emit(
        opts: ActionOpts,
        pending: PendingStacks? = null,
    ): OrderRequest {
        val sigs = ActionCompiler(ExprCompiler(), pendingStacks = pending).compile(Buy("btc", opts)).invoke(ctx)
        return (sigs.single() as Signal.Submit).request
    }

    @Test
    fun `a bracket-less market entry becomes a fill-anchored close-at-market time exit`() {
        val te = emit(opts()) as OrderRequest.TimeExit

        assertThat(te.target).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(te.holdMs).isEqualTo(90_000L)
        assertThat(te.onExpiry).isEqualTo(ExpiryAction.CLOSE_AT_MARKET)
        assertThat(te.quantity).isEqualByComparingTo("0.01")
    }

    @Test
    fun `a bracketed entry keeps its bracket inside the time exit`() {
        val te = emit(opts(bracket = bracket)) as OrderRequest.TimeExit

        assertThat(te.target).isInstanceOf(OrderRequest.Bracket::class.java)
        assertThat(te.entryFillId).isEqualTo((te.target as OrderRequest.Bracket).entry.id)
    }

    @Test
    fun `STACK_AT tiers register on the inner entry and carry the hold`() {
        val pending = PendingStacks()
        val tier =
            StackAtClause(NumLit(BigDecimal("5")), DurationAst(240_000L), SizeQty(NumLit(BigDecimal("0.05"))), bracket)
        val te = emit(opts(stackAts = listOf(tier)), pending) as OrderRequest.TimeExit

        val registered = pending.consume(te.target.id)!!
        assertThat(registered.exitAfterMs).isEqualTo(90_000L)
        assertThat(registered.closeWatchIds).contains("${te.id}-close")
    }

    @Test
    fun `EXIT AFTER is rejected with STACK, an exit OCO, and exit hooks`() {
        val compiler = ActionCompiler(ExprCompiler())
        val stack = opts().copy(stack = StackSpacing(2, NumLit(BigDecimal("1")), StackDirection.TRADE_DIRECTION, null))
        val oco =
            opts().copy(
                oco = OcoAst(stop = ChildBy(NumLit(BigDecimal("5"))), limit = ChildBy(NumLit(BigDecimal("5")))),
            )
        val hooks =
            opts().copy(
                exitHooks =
                    com.qkt.dsl.ast.ExitHooksAst(
                        onClose = listOf(Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE))))),
                    ),
            )

        assertThatThrownBy {
            compiler.compile(Buy("btc", stack))
        }.hasMessageContaining("EXIT AFTER cannot be combined with STACK")
        assertThatThrownBy { compiler.compile(Buy("btc", oco)).invoke(ctx) }.hasMessageContaining("EXIT AFTER")
        assertThatThrownBy { compiler.compile(Buy("btc", hooks)) }.hasMessageContaining("EXIT AFTER")
    }
}
