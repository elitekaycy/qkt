package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestSweepParallelTest {
    private fun ticks(): List<Tick> =
        (1..5).map { i -> Tick("X", Money.of((100 + i).toString()), i * 60_000L) }

    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    @Test
    fun `parallel runs preserve input order`() {
        val sweep =
            BacktestSweep(
                configs = listOf("c1" to 1, "c2" to 2, "c3" to 3, "c4" to 4),
                backtestFactory = { _, config ->
                    Backtest(
                        strategies = listOf("s$config" to noopStrategy),
                        ticks = ticks(),
                        candleWindow = TimeWindow.ONE_MINUTE,
                    )
                },
                parallelism = 2,
            )

        val result = sweep.run()

        assertThat(result.runs.map { it.label }).containsExactly("c1", "c2", "c3", "c4")
        assertThat(result.runs.map { it.config }).containsExactly(1, 2, 3, 4)
    }

    @Test
    fun `parallel and sequential produce equivalent results`() {
        val configs = listOf("a" to 1, "b" to 2, "c" to 3)
        fun build(p: Int) =
            BacktestSweep(
                configs = configs,
                backtestFactory = { _, config ->
                    Backtest(
                        strategies = listOf("s$config" to noopStrategy),
                        ticks = ticks(),
                        candleWindow = TimeWindow.ONE_MINUTE,
                    )
                },
                parallelism = p,
            )

        val seq = build(1).run()
        val par = build(4).run()

        assertThat(par.runs.map { it.label }).isEqualTo(seq.runs.map { it.label })
        for (i in seq.runs.indices) {
            assertThat(par.runs[i].result.global.totalPnL)
                .isEqualByComparingTo(seq.runs[i].result.global.totalPnL)
        }
    }

    @Test
    fun `parallel calls backtestFactory exactly once per config`() {
        val invocations = AtomicInteger(0)
        BacktestSweep(
            configs = listOf("a" to 1, "b" to 2, "c" to 3, "d" to 4),
            backtestFactory = { _, _ ->
                invocations.incrementAndGet()
                Backtest(
                    strategies = listOf("s" to noopStrategy),
                    ticks = ticks(),
                    candleWindow = TimeWindow.ONE_MINUTE,
                )
            },
            parallelism = 4,
        ).run()

        assertThat(invocations.get()).isEqualTo(4)
    }
}
