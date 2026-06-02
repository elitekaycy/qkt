package com.qkt.research

import com.qkt.backtest.SampleCadence
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayEngineTapeTest {
    @Test
    fun `tape records a signal then its fill, and drains empty`() {
        var seen = 0
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    seen += 1
                    if (seen == 2) emit(Signal.Buy("X", Money.of("1")))
                }
            }
        val e =
            ReplayEngine(
                strategies = listOf("s1" to strategy),
                feed = HistoricalTickFeed((1..6).map { Tick("X", Money.of((100 + it).toString()), it * 60_000L) }),
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
            )
        e.advanceToEnd()

        val tape = e.drainTape()
        assertThat(tape).anyMatch { it is TapeEvent.SignalEmitted }
        assertThat(tape).anyMatch { it is TapeEvent.Filled }
        assertThat(e.drainTape()).isEmpty()
    }
}
