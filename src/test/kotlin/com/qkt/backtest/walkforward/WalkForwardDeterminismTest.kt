package com.qkt.backtest.walkforward

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WalkForwardDeterminismTest {
    private val t0 = Instant.parse("2024-01-01T00:00:00Z")

    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private fun ticksForRange(range: TimeRange): List<Tick> {
        val startMs = range.from.toEpochMilli()
        val endMs = range.to.toEpochMilli()
        return generateSequence(startMs) { it + 60_000L }
            .takeWhile { it < endMs }
            .map { ms -> Tick("X", Money.of("100"), ms) }
            .toList()
    }

    private fun newHarness(): WalkForwardHarness<Int> {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(40)))
        return WalkForwardHarness(
            configs = listOf("a" to 1, "b" to 2),
            backtestFactory = { label, _, range ->
                Backtest(
                    strategies = listOf(label to noopStrategy),
                    ticks = ticksForRange(range),
                    candleWindow = TimeWindow.ONE_MINUTE,
                    initialTimestamp = range.from.toEpochMilli(),
                )
            },
            totalRange = total,
            trainSize = Duration.ofDays(20),
            testSize = Duration.ofDays(10),
            stepSize = Duration.ofDays(10),
            scoreOf = { it.global.totalPnL },
        )
    }

    @Test
    fun `two runs produce equal results`() {
        val a = newHarness().run()
        val b = newHarness().run()
        assertThat(a.folds.size).isEqualTo(b.folds.size)
        assertThat(a.winnerCounts).isEqualTo(b.winnerCounts)
        assertThat(a.meanTrainScore).isEqualByComparingTo(b.meanTrainScore)
        assertThat(a.meanTestScore).isEqualByComparingTo(b.meanTestScore)
        assertThat(a.concatenatedTestCurve).isEqualTo(b.concatenatedTestCurve)
    }
}
