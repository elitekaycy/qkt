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

class WalkForwardHarnessTest {
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

    @Test
    fun `harness runs N folds and returns aggregate`() {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(60)))
        val harness =
            WalkForwardHarness(
                configs = listOf("a" to 1, "b" to 2, "c" to 3),
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

        val result = harness.run()

        assertThat(result.folds).hasSize(4)
        assertThat(result.folds.first().trainRange.from).isEqualTo(t0)
        assertThat(result.folds.last().testRange.to).isEqualTo(t0.plus(Duration.ofDays(60)))
        assertThat(result.folds.all { it.winnerLabel in listOf("a", "b", "c") }).isTrue()
        assertThat(result.winnerCounts.values.sum()).isEqualTo(4)
        assertThat(result.concatenatedTestCurve).isNotEmpty()
    }

    @Test
    fun `topConfigs sorted descending and limited to topN`() {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(40)))
        val harness =
            WalkForwardHarness(
                configs = listOf("a" to 1, "b" to 2, "c" to 3, "d" to 4),
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
                topN = 2,
            )
        val result = harness.run()

        for (fold in result.folds) {
            assertThat(fold.topConfigs).hasSizeLessThanOrEqualTo(2)
            for (i in 0 until fold.topConfigs.size - 1) {
                assertThat(fold.topConfigs[i].second)
                    .isGreaterThanOrEqualTo(fold.topConfigs[i + 1].second)
            }
        }
    }

    @Test
    fun `concatenated curve length equals sum of test curve lengths`() {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(40)))
        val harness =
            WalkForwardHarness(
                configs = listOf("a" to 1),
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
        val result = harness.run()

        val expectedLength =
            result.folds.sumOf { it.testResult.global.equityCurve.size }
        assertThat(result.concatenatedTestCurve).hasSize(expectedLength)
    }
}
