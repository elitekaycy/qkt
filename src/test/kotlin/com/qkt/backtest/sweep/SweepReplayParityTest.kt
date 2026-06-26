package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest
import com.qkt.backtest.SampleCadence
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.research.ReplayEngine
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The fan-out sweep ([SweepReplay]) must be bit-identical to running each combo as its own backtest
 * ([BacktestSweep]). The strategy here trades at a parameter-controlled tick, so the combos produce
 * genuinely different results — a vacuous all-zero parity would not prove anything.
 */
class SweepReplayParityTest {
    private fun ticks() = (1..10).map { Tick("X", Money.of((100 + it).toString()), it * 60_000L) }

    /** Buys on the buyTick-th tick, sells on a fixed later tick: distinct buyTick -> distinct entry
     *  price -> distinct PnL (sell price is constant, so a later entry earns less). */
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

    private val configs = listOf("b2" to 2, "b3" to 3, "b4" to 4)

    private fun perCombo(parallelism: Int) =
        BacktestSweep(
            configs = configs,
            backtestFactory = { _, buyTick ->
                Backtest(
                    strategies = listOf("s" to buyAtThenSell(buyTick)),
                    ticks = ticks(),
                    candleWindow = TimeWindow.ONE_MINUTE,
                )
            },
            parallelism = parallelism,
        ).run()

    private fun fanned(parallelism: Int) =
        SweepReplay(
            configs = configs,
            sharedFeed = { HistoricalTickFeed(ticks()) },
            engineFor = { _, buyTick ->
                ReplayEngine(
                    strategies = listOf("s" to buyAtThenSell(buyTick)),
                    feed = SequenceTickFeed(emptySequence()),
                    candleWindow = TimeWindow.ONE_MINUTE,
                    cadence = SampleCadence.CANDLE_CLOSE,
                )
            },
            parallelism = parallelism,
        ).run()

    @Test
    fun `fan-out is bit-identical to per-combo sweep`() {
        val legacy = perCombo(1).runs
        val fan = fanned(1).runs

        assertThat(fan.map { it.label }).isEqualTo(legacy.map { it.label }) // input order preserved
        // The combos must genuinely differ, or the parity assertion proves nothing.
        assertThat(
            legacy
                .map {
                    it.result.global.totalPnL
                        .toPlainString()
                }.distinct()
                .size,
        ).isGreaterThan(1)
        for ((a, b) in legacy.zip(fan)) {
            assertThat(b.config).isEqualTo(a.config)
            assertThat(b.result.global.tradeCount).isEqualTo(a.result.global.tradeCount)
            assertThat(b.result.global.totalPnL).isEqualByComparingTo(a.result.global.totalPnL)
            assertThat(b.result.global.maxDrawdown).isEqualByComparingTo(a.result.global.maxDrawdown)
            assertThat(b.result.trades).isEqualTo(a.result.trades)
        }
    }

    @Test
    fun `parallel fan-out matches sequential fan-out`() {
        val seq = fanned(1).runs.associateBy { it.label }
        val par = fanned(2).runs.associateBy { it.label }
        assertThat(par.keys).isEqualTo(seq.keys)
        for (label in seq.keys) {
            assertThat(
                par
                    .getValue(label)
                    .result.global.totalPnL,
            ).isEqualByComparingTo(
                seq
                    .getValue(label)
                    .result.global.totalPnL,
            )
            assertThat(par.getValue(label).result.trades).isEqualTo(seq.getValue(label).result.trades)
        }
    }

    @Test
    fun `fan-out decodes the shared feed once per worker not once per combo`() {
        val feeds = AtomicInteger(0)

        SweepReplay(
            configs = listOf("b2" to 2, "b3" to 3, "b4" to 4, "b5" to 5),
            sharedFeed = {
                feeds.incrementAndGet()
                HistoricalTickFeed(ticks())
            },
            engineFor = { _, buyTick ->
                ReplayEngine(
                    strategies = listOf("s" to buyAtThenSell(buyTick)),
                    feed = SequenceTickFeed(emptySequence()),
                    candleWindow = TimeWindow.ONE_MINUTE,
                    cadence = SampleCadence.CANDLE_CLOSE,
                )
            },
            parallelism = 2,
        ).run()

        assertThat(feeds.get()).isEqualTo(2)
    }
}
