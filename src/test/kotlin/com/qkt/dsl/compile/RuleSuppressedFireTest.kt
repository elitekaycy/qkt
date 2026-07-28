package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.WhenThen
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A rule edge is only consumed when a fire produced an accepted signal (or recorded no
 * outcome at all — plain harnesses). A fire whose every signal the pipeline marked
 * suppressed re-arms, so a live-only blocker (portfolio gate, book de-risk, risk reject)
 * cannot permanently eat an entry the backtest would take.
 */
class RuleSuppressedFireTest {
    private fun strategyWith(cond: ExprAst): Strategy {
        val ast =
            StrategyAst(
                name = "edge",
                version = 1,
                streams = listOf(StreamDecl("btc", "BACKTEST", "BTCUSDT", "1m")),
                constants = emptyList(),
                lets = emptyList(),
                defaults = null,
                rules =
                    listOf(
                        WhenThen(
                            cond = cond,
                            action = Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)))),
                        ),
                    ),
            )
        return AstCompiler().compile(ast)
    }

    private val above100: ExprAst = CmpOp(Cmp.GT, StreamFieldRef("btc", "close"), NumLit(BigDecimal("100")))

    private fun feed(
        strategy: Strategy,
        prices: List<String>,
        ctx: StrategyContext,
        onSignal: (Signal, StrategyContext) -> Unit,
    ): List<Signal> {
        val captured = mutableListOf<Signal>()
        for ((i, price) in prices.withIndex()) {
            val p = BigDecimal(price)
            val c = Candle("BACKTEST:BTCUSDT", p, p, p, p, BigDecimal.ZERO, i * 60_000L, (i + 1) * 60_000L)
            strategy.onCandle(c, ctx) { sig ->
                captured.add(sig)
                onSignal(sig, ctx)
            }
        }
        return captured
    }

    @Test
    fun `fire whose signals were all suppressed re-fires while the condition holds`() {
        val ctx = testStrategyContext()
        val signals =
            feed(strategyWith(above100), listOf("90", "150", "160", "170"), ctx) { _, c ->
                c.submissions.recordSuppressed()
            }
        assertThat(signals).hasSize(3)
    }

    @Test
    fun `accepted fire consumes the edge as before`() {
        val ctx = testStrategyContext()
        val signals =
            feed(strategyWith(above100), listOf("90", "150", "160", "170"), ctx) { _, c ->
                c.submissions.recordAccepted()
            }
        assertThat(signals).hasSize(1)
    }

    @Test
    fun `re-armed edge stops firing once a retry is accepted`() {
        val ctx = testStrategyContext()
        var fires = 0
        val signals =
            feed(strategyWith(above100), listOf("90", "150", "160", "170", "180"), ctx) { _, c ->
                fires++
                if (fires == 1) c.submissions.recordSuppressed() else c.submissions.recordAccepted()
            }
        assertThat(signals).hasSize(2)
    }
}
