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

    @Test
    fun `a busy strategy replays to an identical trade tape, not just equal totals`() {
        // A seeded random walk and a churny threshold strategy: two replays must agree
        // on EVERY trade (id, price, qty, side, timestamp), every rejection, and the
        // full equity curve — totals matching is necessary, not sufficient.
        fun run(): BacktestResult {
            val rng = kotlin.random.Random(42)
            var price = 10_000.0
            val ticks =
                (1..3_000).map {
                    price += rng.nextDouble(-5.0, 5.0)
                    Tick("X", Money.of(String.format("%.2f", price)), it * 1_000L)
                }
            var long = false
            val strategy =
                object : Strategy {
                    override fun onTick(
                        tick: Tick,
                        ctx: StrategyContext,
                        emit: (Signal) -> Unit,
                    ) {
                        val p = tick.price.toDouble()
                        if (!long && p < 9_990.0) {
                            emit(Signal.Buy("X", Money.of("1")))
                            long = true
                        } else if (long && p > 10_010.0) {
                            emit(Signal.Sell("X", Money.of("1")))
                            long = false
                        }
                    }
                }
            return Backtest(
                strategies = listOf("walk" to strategy),
                ticks = ticks,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        }

        val a = run()
        val b = run()
        assertThat(a.trades).isNotEmpty
        assertThat(a.trades.map { it.trade }).isEqualTo(b.trades.map { it.trade })
        assertThat(a.rejections.map { it.reason }).isEqualTo(b.rejections.map { it.reason })
        assertThat(a.global.equityCurve).isEqualTo(b.global.equityCurve)
        assertThat(a.halts).isEqualTo(b.halts)
    }
}
