package com.qkt.backtest.walkforward

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WalkForwardHarnessFailFastTest {
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
    fun `harness propagates exception from backtestFactory`() {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(40)))
        val harness =
            WalkForwardHarness(
                configs = listOf("ok" to 1, "boom" to 2),
                backtestFactory = { _, config, range ->
                    if (config == 2) error("boom from config 2")
                    Backtest(
                        strategies = listOf("s" to noopStrategy),
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

        assertThatThrownBy { harness.run() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("boom from config 2")
    }
}
