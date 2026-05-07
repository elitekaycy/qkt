package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestEndToEndTest {
    @Test
    fun `single buy then sell produces nonempty per-strategy report`() {
        val ticks = (1..10).map { Tick("X", Money.of((100 + it).toString()), it * 60_000L) }
        var ticksSeen = 0
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    ticksSeen += 1
                    if (ticksSeen == 2) emit(Signal.Buy("X", Money.of("1")))
                    if (ticksSeen == 8) emit(Signal.Sell("X", Money.of("1")))
                }
            }

        val backtest =
            Backtest(
                strategies = listOf("s1" to strategy),
                ticks = ticks,
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
            )
        val result = backtest.run()

        assertThat(result.global.tradeCount).isGreaterThan(0)
        assertThat(result.global.equityCurve).isNotEmpty()
        assertThat(result.perStrategy).containsKey("s1")
        assertThat(result.perStrategy["s1"]!!.equityCurve).isNotEmpty()
        assertThat(result.cadence).isEqualTo(SampleCadence.CANDLE_CLOSE)
    }
}
