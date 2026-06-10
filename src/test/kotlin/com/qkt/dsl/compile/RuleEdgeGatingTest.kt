package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
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
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Rule actions are edge-driven (reference/dsl/conditions.md): a condition that stays
 * true fires its action once, on the false-to-true transition, not on every bar.
 */
class RuleEdgeGatingTest {
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

    private fun feed(
        strategy: Strategy,
        prices: List<String>,
    ): List<Signal> {
        val captured = mutableListOf<Signal>()
        val ctx = testStrategyContext()
        for ((i, price) in prices.withIndex()) {
            val p = BigDecimal(price)
            val c = Candle("BACKTEST:BTCUSDT", p, p, p, p, BigDecimal.ZERO, i * 60_000L, (i + 1) * 60_000L)
            strategy.onCandle(c, ctx, captured::add)
        }
        return captured
    }

    private val above100: ExprAst = CmpOp(Cmp.GT, StreamFieldRef("btc", "close"), NumLit(BigDecimal("100")))

    @Test
    fun `sustained-true condition fires the action once`() {
        val signals = feed(strategyWith(above100), listOf("90", "150", "160", "170", "180"))
        assertThat(signals).hasSize(1)
        assertThat(signals.first()).isInstanceOf(Signal.Buy::class.java)
    }

    @Test
    fun `condition that drops and re-arms fires again`() {
        val signals = feed(strategyWith(above100), listOf("90", "150", "90", "150", "150"))
        assertThat(signals).hasSize(2)
    }

    @Test
    fun `condition true on the first evaluated bar fires`() {
        val signals = feed(strategyWith(above100), listOf("150", "160"))
        assertThat(signals).hasSize(1)
    }

    @Test
    fun `CROSSES keeps firing once per crossing`() {
        val cond =
            Crosses(
                lhs = StreamFieldRef("btc", "close"),
                rhs = NumLit(BigDecimal("100")),
                direction = CrossDir.ABOVE,
            )
        val signals = feed(strategyWith(cond), listOf("90", "150", "150", "90", "150"))
        assertThat(signals).hasSize(2)
    }
}
