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

class BacktestSweepSequentialTest {
    private fun ticks(): List<Tick> = (1..5).map { i -> Tick("X", Money.of((100 + i).toString()), i * 60_000L) }

    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    @Test
    fun `runs each config once and preserves input order`() {
        val invocations = AtomicInteger(0)
        val sweep =
            BacktestSweep(
                configs = listOf("c1" to 1, "c2" to 2, "c3" to 3),
                backtestFactory = { label, config ->
                    invocations.incrementAndGet()
                    assertThat(label).startsWith("c")
                    Backtest(
                        strategies = listOf("s$config" to noopStrategy),
                        ticks = ticks(),
                        candleWindow = TimeWindow.ONE_MINUTE,
                    )
                },
                parallelism = 1,
            )

        val result = sweep.run()

        assertThat(invocations.get()).isEqualTo(3)
        assertThat(result.runs.map { it.label }).containsExactly("c1", "c2", "c3")
        assertThat(result.runs.map { it.config }).containsExactly(1, 2, 3)
        assertThat(result.runs.all { it.result.global.tradeCount == 0 }).isTrue()
    }

    @Test
    fun `result objects carry label config and BacktestResult`() {
        val sweep =
            BacktestSweep(
                configs = listOf("only" to "value"),
                backtestFactory = { _, _ ->
                    Backtest(
                        strategies = listOf("s" to noopStrategy),
                        ticks = ticks(),
                        candleWindow = TimeWindow.ONE_MINUTE,
                    )
                },
            )

        val result = sweep.run()
        val run = result.runs.single()
        assertThat(run.label).isEqualTo("only")
        assertThat(run.config).isEqualTo("value")
        assertThat(run.result.cadence).isNotNull()
    }
}
