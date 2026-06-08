package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KeltnerChannelsTest {
    private fun candle(
        h: String,
        l: String,
        c: String,
    ) = Candle("X", Money.of(c), Money.of(h), Money.of(l), Money.of(c), Money.of("1"), 0L, 1L)

    @Test
    fun `not ready before warmup`() {
        val k = KeltnerChannels(5, BigDecimal(2))
        repeat(3) { k.update(candle("11", "9", "10")) }
        assertThat(k.isReady).isFalse()
        assertThat(k.bands()).isNull()
        assertThat(k.warmupBars).isEqualTo(6)
    }

    @Test
    fun `flat candles collapse all bands to the EMA`() {
        // ATR of flat candles is 0, so upper = middle = lower = the constant close.
        val k = KeltnerChannels(5, BigDecimal(2))
        repeat(12) { k.update(candle("100", "100", "100")) }
        val bands = k.bands()!!
        assertThat(bands.upper).isEqualByComparingTo(Money.of("100"))
        assertThat(bands.middle).isEqualByComparingTo(Money.of("100"))
        assertThat(bands.lower).isEqualByComparingTo(Money.of("100"))
    }

    @Test
    fun `upper sits above middle above lower when there is range`() {
        val k = KeltnerChannels(5, BigDecimal(2))
        repeat(12) { k.update(candle("105", "95", "100")) }
        val bands = k.bands()!!
        assertThat(bands.upper).isGreaterThan(bands.middle)
        assertThat(bands.middle).isGreaterThan(bands.lower)
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { KeltnerChannels(0, BigDecimal(2)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
