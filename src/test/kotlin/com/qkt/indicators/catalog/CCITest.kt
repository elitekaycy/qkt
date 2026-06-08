package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CCITest {
    private fun candle(
        h: String,
        l: String,
        c: String,
    ) = Candle("X", Money.of(c), Money.of(h), Money.of(l), Money.of(c), Money.of("1"), 0L, 1L)

    @Test
    fun `not ready before warmup`() {
        val cci = CCI(3)
        cci.update(candle("1", "1", "1"))
        cci.update(candle("2", "2", "2"))
        assertThat(cci.isReady).isFalse()
        assertThat(cci.value()).isNull()
        assertThat(cci.warmupBars).isEqualTo(3)
    }

    @Test
    fun `computes against typical price and mean deviation`() {
        // TPs 1,2,3 → SMA 2, meanDev 2/3 → (3-2)/(0.015*2/3) = 1/0.01 = 100.
        val cci = CCI(3)
        cci.update(candle("1", "1", "1"))
        cci.update(candle("2", "2", "2"))
        cci.update(candle("3", "3", "3"))
        assertThat(cci.value()!!.toDouble()).isEqualTo(100.0)
    }

    @Test
    fun `flat series yields zero`() {
        val cci = CCI(3)
        repeat(3) { cci.update(candle("5", "5", "5")) }
        assertThat(cci.value()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { CCI(0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
