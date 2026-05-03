package com.qkt.strategy

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EveryNthTickBuyStrategyTest {
    private fun tick(
        symbol: String = "XAUUSD",
        price: BigDecimal = Money.of("2400.0"),
        ts: Long = 0L,
    ) = Tick(symbol, price, ts)

    @Test
    fun `emits no signal on first n minus 1 ticks`() {
        val signals = mutableListOf<Signal>()
        val strategy = EveryNthTickBuyStrategy("XAUUSD", n = 10)
        repeat(9) { strategy.onTick(tick()) { signals.add(it) } }
        assertThat(signals).isEmpty()
    }

    @Test
    fun `emits Buy on the nth tick`() {
        val signals = mutableListOf<Signal>()
        val strategy = EveryNthTickBuyStrategy("XAUUSD", n = 10, size = Money.of("1"))
        repeat(10) { strategy.onTick(tick()) { signals.add(it) } }
        assertThat(signals).hasSize(1)
        assertThat(signals[0]).isEqualTo(Signal.Buy("XAUUSD", Money.of("1")))
    }

    @Test
    fun `emits Buy on every nth tick`() {
        val signals = mutableListOf<Signal>()
        val strategy = EveryNthTickBuyStrategy("XAUUSD", n = 5, size = Money.of("2"))
        repeat(15) { strategy.onTick(tick()) { signals.add(it) } }
        assertThat(signals).hasSize(3)
        assertThat(signals).allMatch { it == Signal.Buy("XAUUSD", Money.of("2")) }
    }

    @Test
    fun `ignores ticks for non-target symbol`() {
        val signals = mutableListOf<Signal>()
        val strategy = EveryNthTickBuyStrategy("XAUUSD", n = 1)
        strategy.onTick(tick("EURUSD")) { signals.add(it) }
        strategy.onTick(tick("GBPUSD")) { signals.add(it) }
        assertThat(signals).isEmpty()
    }

    @Test
    fun `throws on non-positive n or size`() {
        assertThatThrownBy { EveryNthTickBuyStrategy("XAUUSD", n = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EveryNthTickBuyStrategy("XAUUSD", n = 1, size = Money.of("0")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
