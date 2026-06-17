package com.qkt.backtest.sweep

import com.qkt.backtest.SampleCadence
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.Tick
import com.qkt.research.ReplayEngine
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Probe: does running two combos as two strategies in ONE engine (shared decode/aggregation/price
 * tracking) yield per-strategy results identical to running each combo as its own backtest? If yes,
 * a multi-strategy sweep is the deep speedup with no hot-path rewrite. No halt rules here, so the
 * only coupling under test is the shared market state + broker, not account-level halts.
 */
class MultiStrategyIsolationProbeTest {
    private fun ticks() = (1..10).map { Tick("X", Money.of((100 + it).toString()), it * 60_000L) }

    private fun buyAtThenSell(buyTick: Int): Strategy {
        var seen = 0
        return object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                seen += 1
                if (seen == buyTick) emit(Signal.Buy("X", Money.of("1")))
                if (seen == 9) emit(Signal.Sell("X", Money.of("1")))
            }
        }
    }

    private fun solo(buyTick: Int) =
        ReplayEngine(
            strategies = listOf("s" to buyAtThenSell(buyTick)),
            feed = HistoricalTickFeed(ticks()),
            candleWindow = TimeWindow.ONE_MINUTE,
            cadence = SampleCadence.CANDLE_CLOSE,
            startingBalance = Money.of("10000"),
        ).runToEnd()

    @Test
    fun `two combos in one engine match standalone per-strategy`() {
        val a = solo(2)
        val b = solo(4)

        val multi =
            ReplayEngine(
                strategies = listOf("A" to buyAtThenSell(2), "B" to buyAtThenSell(4)),
                feed = HistoricalTickFeed(ticks()),
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
                startingBalance = Money.of("10000"),
            ).runToEnd()

        val pa = multi.perStrategy.getValue("A")
        val pb = multi.perStrategy.getValue("B")

        // Combos must differ, else this proves nothing.
        assertThat(a.global.totalPnL).isNotEqualByComparingTo(b.global.totalPnL)

        // The sweep-relevant metrics must match standalone for each combo.
        assertThat(pa.tradeCount).isEqualTo(a.global.tradeCount)
        assertThat(pa.totalPnL).isEqualByComparingTo(a.global.totalPnL)
        assertThat(pa.sharpeRatio).isEqualTo(a.global.sharpeRatio)
        assertThat(pa.maxDrawdown).isEqualByComparingTo(a.global.maxDrawdown)
        assertThat(pb.tradeCount).isEqualTo(b.global.tradeCount)
        assertThat(pb.totalPnL).isEqualByComparingTo(b.global.totalPnL)
        assertThat(pb.sharpeRatio).isEqualTo(b.global.sharpeRatio)
        assertThat(pb.maxDrawdown).isEqualByComparingTo(b.global.maxDrawdown)
    }
}
