package com.qkt.strategy

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EmaCrossoverStrategyTest {
    private fun candle(
        close: String,
        ts: Long = 0L,
        symbol: String = "X",
    ) = Candle(
        symbol = symbol,
        open = Money.of(close),
        high = Money.of(close),
        low = Money.of(close),
        close = Money.of(close),
        volume = Money.of("1"),
        startTime = ts,
        endTime = ts + 1,
    )

    private fun feed(
        strategy: EmaCrossoverStrategy,
        closes: List<String>,
    ): List<Signal> {
        val signals = mutableListOf<Signal>()
        closes.forEachIndexed { i, c ->
            strategy.onCandle(candle(c, ts = i.toLong()), testStrategyContext()) { signals.add(it) }
        }
        return signals
    }

    @Test
    fun `no signal during warmup`() {
        val strategy = EmaCrossoverStrategy("X", fastPeriod = 2, slowPeriod = 3)
        val signals = feed(strategy, listOf("10", "10"))
        assertThat(signals).isEmpty()
    }

    @Test
    fun `golden cross emits Buy`() {
        val strategy =
            EmaCrossoverStrategy(
                symbol = "X",
                fastPeriod = 2,
                slowPeriod = 3,
                size = Money.of("1"),
            )
        val signals = feed(strategy, listOf("10", "10", "10", "10", "12"))
        assertThat(signals).containsExactly(Signal.Buy("X", Money.of("1")))
    }

    @Test
    fun `death cross emits Sell after a prior golden cross`() {
        val strategy =
            EmaCrossoverStrategy(
                symbol = "X",
                fastPeriod = 2,
                slowPeriod = 3,
                size = Money.of("1"),
            )
        val signals = feed(strategy, listOf("10", "10", "10", "10", "12", "14", "12", "10"))
        assertThat(signals).containsExactly(
            Signal.Buy("X", Money.of("1")),
            Signal.Sell("X", Money.of("1")),
        )
    }

    @Test
    fun `rejects invalid parameters`() {
        assertThatThrownBy { EmaCrossoverStrategy("X", fastPeriod = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EmaCrossoverStrategy("X", fastPeriod = 21, slowPeriod = 9) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EmaCrossoverStrategy("X", size = Money.of("0")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
