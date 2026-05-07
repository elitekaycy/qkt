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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WalkForwardHarnessValidationTest {
    private val t0 = Instant.parse("2024-01-01T00:00:00Z")
    private val total = TimeRange(t0, t0.plus(Duration.ofDays(60)))

    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private fun bt(): Backtest =
        Backtest(
            strategies = listOf("s" to noopStrategy),
            ticks = listOf(Tick("X", Money.of("100"), 1L)),
            candleWindow = TimeWindow.ONE_MINUTE,
        )

    @Test
    fun `parallelism less than 1 fails`() {
        assertThatThrownBy {
            WalkForwardHarness(
                configs = listOf("a" to 1),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
                parallelism = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("parallelism")
    }

    @Test
    fun `topN less than 1 fails`() {
        assertThatThrownBy {
            WalkForwardHarness(
                configs = listOf("a" to 1),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
                topN = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("topN")
    }

    @Test
    fun `non-positive sizes fail`() {
        assertThatThrownBy {
            WalkForwardHarness(
                configs = listOf("a" to 1),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = total,
                trainSize = Duration.ZERO,
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("trainSize")
    }

    @Test
    fun `empty configs fails`() {
        assertThatThrownBy {
            WalkForwardHarness<Int>(
                configs = emptyList(),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("configs")
    }

    @Test
    fun `duplicate labels fail`() {
        assertThatThrownBy {
            WalkForwardHarness(
                configs = listOf("a" to 1, "a" to 2),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unique")
    }

    @Test
    fun `total range too short fails`() {
        assertThatThrownBy {
            WalkForwardHarness(
                configs = listOf("a" to 1),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = TimeRange(t0, t0.plus(Duration.ofDays(20))),
                trainSize = Duration.ofDays(15),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(5),
                scoreOf = { BigDecimal.ZERO },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("totalRange")
    }

    @Test
    fun `valid construction succeeds`() {
        val harness =
            WalkForwardHarness(
                configs = listOf("a" to 1),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
            )
        assertThat(harness).isNotNull()
    }
}
