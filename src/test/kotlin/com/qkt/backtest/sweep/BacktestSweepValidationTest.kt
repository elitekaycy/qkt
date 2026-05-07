package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BacktestSweepValidationTest {
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
            ticks = listOf(Tick("X", Money.of("100"), 1_000L)),
            candleWindow = TimeWindow.ONE_MINUTE,
        )

    @Test
    fun `parallelism less than 1 fails`() {
        assertThatThrownBy {
            BacktestSweep(
                configs = listOf("a" to 1),
                backtestFactory = { _, _ -> bt() },
                parallelism = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("parallelism must be >= 1")
    }

    @Test
    fun `empty configs fails`() {
        assertThatThrownBy {
            BacktestSweep<Int>(
                configs = emptyList(),
                backtestFactory = { _, _ -> bt() },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("configs must not be empty")
    }

    @Test
    fun `duplicate labels fail`() {
        assertThatThrownBy {
            BacktestSweep(
                configs = listOf("a" to 1, "a" to 2),
                backtestFactory = { _, _ -> bt() },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("config labels must be unique")
    }

    @Test
    fun `blank label fails`() {
        assertThatThrownBy {
            BacktestSweep(
                configs = listOf("" to 1),
                backtestFactory = { _, _ -> bt() },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("config labels must be non-blank")
    }

    @Test
    fun `valid construction succeeds`() {
        val sweep =
            BacktestSweep(
                configs = listOf("a" to 1, "b" to 2),
                backtestFactory = { _, _ -> bt() },
                parallelism = 1,
            )
        assertThat(sweep).isNotNull()
    }
}
