package com.qkt.app

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.compile.AggregateBinding
import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.compile.IndicatorBinding
import com.qkt.dsl.portfolio.CompiledChild
import com.qkt.dsl.portfolio.PortfolioCompiled
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioStrategyTest {
    private class Recorder : Strategy {
        val ticks: MutableList<Tick> = mutableListOf()

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            ticks.add(tick)
        }
    }

    private fun newPortfolio(
        children: List<CompiledChild>,
        rules: List<com.qkt.dsl.ast.PortfolioRule>,
    ): PortfolioStrategy {
        val ast =
            PortfolioAst(
                name = "p",
                version = 1,
                streams = emptyList(),
                imports = children.map { ImportClause(path = "${it.alias}.qkt", alias = it.alias) },
                rules = rules,
            )
        val compiled = PortfolioCompiled(ast, children)
        return PortfolioStrategy(
            compiled,
            ExprCompiler(IndicatorBinding.Bag(), AggregateBinding.Bag()),
        )
    }

    @Test
    fun `bare RUN child receives all ticks`() {
        val recorder = Recorder()
        val child =
            CompiledChild(
                alias = "x",
                hold = false,
                strategyId = "p:x",
                compiled = recorder,
                streams = listOf("btc"),
                symbols = listOf("BTCUSDT"),
                ast =
                    StrategyAst(
                        name = "x",
                        version = 1,
                        streams =
                            listOf(
                                StreamDecl(alias = "btc", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m"),
                            ),
                        constants = emptyList(),
                        lets = emptyList(),
                        defaults = null,
                        rules = emptyList<WhenThen>(),
                    ),
            )
        val pf = newPortfolio(listOf(child), listOf(AlwaysRun("x")))

        val ctx = testStrategyContext()
        val tick1 = Tick("BTCUSDT", BigDecimal("50000"), 1L)
        val tick2 = Tick("BTCUSDT", BigDecimal("50100"), 2L)
        pf.onTick(tick1, ctx) {}
        pf.onTick(tick2, ctx) {}

        assertThat(recorder.ticks).containsExactly(tick1, tick2)
    }
}
