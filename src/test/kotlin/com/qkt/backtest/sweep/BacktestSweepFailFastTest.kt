package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BacktestSweepFailFastTest {
    private fun ticks(): List<Tick> =
        (1..3).map { i -> Tick("X", Money.of((100 + i).toString()), i * 60_000L) }

    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private fun bt(): Backtest =
        Backtest(
            strategies = listOf("s" to noopStrategy),
            ticks = ticks(),
            candleWindow = TimeWindow.ONE_MINUTE,
        )

    @Test
    fun `sequential propagates first exception immediately`() {
        val sweep =
            BacktestSweep(
                configs = listOf("ok" to 1, "boom" to 2, "never" to 3),
                backtestFactory = { _, config ->
                    if (config == 2) error("boom from config 2")
                    bt()
                },
                parallelism = 1,
            )

        assertThatThrownBy { sweep.run() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("boom from config 2")
    }

    @Test
    fun `parallel propagates the first exception in input order`() {
        val sweep =
            BacktestSweep(
                configs = listOf("ok" to 1, "boom" to 2, "ok2" to 3),
                backtestFactory = { _, config ->
                    if (config == 2) error("boom from config 2")
                    bt()
                },
                parallelism = 3,
            )

        assertThatThrownBy { sweep.run() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("boom from config 2")
    }
}
