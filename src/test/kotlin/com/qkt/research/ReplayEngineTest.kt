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

class ReplayEngineTest {
    private fun ticks() = (1..10).map { Tick("X", Money.of((100 + it).toString()), it * 60_000L) }

    private fun buyThenSell(): Strategy {
        var seen = 0
        return object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                seen += 1
                if (seen == 2) emit(Signal.Buy("X", Money.of("1")))
                if (seen == 8) emit(Signal.Sell("X", Money.of("1")))
            }
        }
    }

    private fun engine(strategy: Strategy = buyThenSell()) =
        ReplayEngine(
            strategies = listOf("s1" to strategy),
            feed = HistoricalTickFeed(ticks()),
            candleWindow = TimeWindow.ONE_MINUTE,
            cadence = SampleCadence.CANDLE_CLOSE,
        )

    @Test
    fun `run to end produces trades and an equity curve`() {
        val result = engine().runToEnd()
        assertThat(result.global.tradeCount).isGreaterThan(0)
        assertThat(result.global.equityCurve).isNotEmpty()
        assertThat(result.cadence).isEqualTo(SampleCadence.CANDLE_CLOSE)
    }

    @Test
    fun `stepping in increments reaches the same end state as run-to-end`() {
        val whole = engine().runToEnd()

        val stepped = engine()
        stepped.advanceUntil { stepped.barsClosed >= 3 }
        stepped.advanceUntil { stepped.currentTimestamp >= 7 * 60_000L }
        stepped.advanceToEnd()
        val steppedResult = stepped.snapshot()

        assertThat(steppedResult.global.totalPnL).isEqualByComparingTo(whole.global.totalPnL)
        assertThat(steppedResult.global.tradeCount).isEqualTo(whole.global.tradeCount)
        assertThat(stepped.exhausted).isTrue()
    }

    @Test
    fun `bars closed counts candle closes`() {
        val e = engine()
        e.advanceToEnd()
        assertThat(e.barsClosed).isGreaterThan(0)
    }
}
