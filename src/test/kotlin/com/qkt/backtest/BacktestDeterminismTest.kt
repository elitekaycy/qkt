package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestDeterminismTest {
    private fun newBacktest(): Backtest {
        val ticks = (1..30).map { Tick("X", Money.of((100 + it % 5).toString()), it * 60_000L) }
        var n = 0
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    n += 1
                    if (n == 5) emit(Signal.Buy("X", Money.of("1")))
                    if (n == 20) emit(Signal.Sell("X", Money.of("1")))
                }
            }
        return Backtest(
            strategies = listOf("s1" to strategy),
            ticks = ticks,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }

    @Test
    fun `two runs produce equal results`() {
        val a = newBacktest().run()
        val b = newBacktest().run()
        assertThat(a.global.totalPnL).isEqualByComparingTo(b.global.totalPnL)
        assertThat(a.global.equityCurve).isEqualTo(b.global.equityCurve)
        assertThat(a.global.maxDrawdown).isEqualByComparingTo(b.global.maxDrawdown)
    }
}
