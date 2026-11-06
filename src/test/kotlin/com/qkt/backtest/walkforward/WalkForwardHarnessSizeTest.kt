package com.qkt.backtest.walkforward

import com.qkt.backtest.Backtest
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

class WalkForwardHarnessSizeTest {
    private val t0 = Instant.parse("2024-01-01T00:00:00Z")
    private val idle =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }

    private fun harness(
        test: Duration,
        step: Duration,
    ) = WalkForwardHarness(
        configs = listOf("a" to 1),
        backtestFactory = {
            _,
            _,
            _,
            ->
            Backtest(strategies = listOf("s" to idle), ticks = listOf(Tick("X", Money.of("1"), 1L)))
        },
        totalRange = TimeRange(t0, t0.plus(Duration.ofDays(60))),
        trainSize = Duration.ofDays(20),
        testSize = test,
        stepSize = step,
        scoreOf = { BigDecimal.ZERO },
    )

    @Test
    fun `a zero or negative test window is refused`() {
        assertThatThrownBy { harness(Duration.ZERO, Duration.ofDays(10)) }
            .hasMessage("testSize must be positive, got PT0S")
        assertThatThrownBy { harness(Duration.ofDays(-1), Duration.ofDays(10)) }
            .hasMessageContaining("testSize must be positive")
    }

    @Test
    fun `a zero or negative step is refused`() {
        assertThatThrownBy { harness(Duration.ofDays(10), Duration.ZERO) }
            .hasMessage("stepSize must be positive, got PT0S")
        assertThatThrownBy { harness(Duration.ofDays(10), Duration.ofDays(-2)) }
            .hasMessageContaining("stepSize must be positive")
    }
}
