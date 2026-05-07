package com.qkt.backtest.report

import com.qkt.backtest.Backtest
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.walkforward.WalkForwardHarness
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WalkForwardReportWriterTest {
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

    private fun newHarness(): WalkForwardHarness<String> {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(40)))
        return WalkForwardHarness(
            configs = listOf("ema_9_21" to "fast=9_slow=21", "ema_12_26" to "fast=12_slow=26"),
            backtestFactory = { label, _, range ->
                Backtest(
                    strategies = listOf(label to noopStrategy),
                    ticks = ticksForRange(range),
                    candleWindow = TimeWindow.ONE_MINUTE,
                    initialTimestamp = range.from.toEpochMilli(),
                    cadence = SampleCadence.CANDLE_CLOSE,
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
    fun `writer produces summary plus per-fold dirs`(
        @TempDir dir: Path,
    ) {
        val result = newHarness().run()

        WalkForwardReportWriter(dir).write(result)

        assertThat(dir.resolve("walkforward_summary.json")).exists()
        assertThat(dir.resolve("walkforward_summary.csv")).exists()
        assertThat(dir.resolve("concatenated_equity.csv")).exists()
        assertThat(dir.resolve("winner_counts.csv")).exists()
        for (i in 1..result.folds.size) {
            val padded = "fold_%03d".format(i)
            assertThat(dir.resolve("folds/$padded/result.json")).exists()
            assertThat(dir.resolve("folds/$padded/equity_global.csv")).exists()
        }

        val csv = Files.readString(dir.resolve("walkforward_summary.csv"))
        val lines = csv.trim().lines()
        assertThat(lines.size).isEqualTo(result.folds.size + 1)
        assertThat(lines[0]).contains("foldIndex,trainStart,trainEnd,testStart,testEnd")

        val eq = Files.readString(dir.resolve("concatenated_equity.csv"))
        assertThat(eq.lines().first()).isEqualTo("timestamp,equity")
    }

    @Test
    fun `unsafe winner label is rejected before any file is written`(
        @TempDir dir: Path,
    ) {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(40)))
        val harness =
            WalkForwardHarness(
                configs = listOf("../danger" to "ok"),
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
                scoreOf = { BigDecimal.ZERO },
            )
        val result = harness.run()

        assertThatThrownBy { WalkForwardReportWriter(dir).write(result) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Files.list(dir).count()).isEqualTo(0L)
    }
}
