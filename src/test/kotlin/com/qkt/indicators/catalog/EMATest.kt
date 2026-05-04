package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EMATest {
    @Test
    fun `not ready before bar N`() {
        val ema = EMA(3)
        ema.update(Money.of("10"))
        ema.update(Money.of("20"))
        assertThat(ema.isReady).isFalse()
        assertThat(ema.value()).isNull()
        assertThat(ema.warmupBars).isEqualTo(3)
    }

    @Test
    fun `seeds with SMA of first N closes`() {
        val ema = EMA(3)
        listOf("10", "20", "30").forEach { ema.update(Money.of(it)) }
        assertThat(ema.isReady).isTrue()
        assertThat(ema.value()).isEqualByComparingTo(Money.of("20"))
    }

    @Test
    fun `applies alpha smoothing after seed`() {
        val ema = EMA(3)
        listOf("10", "20", "30", "40", "50").forEach { ema.update(Money.of(it)) }
        assertThat(ema.value()).isEqualByComparingTo(Money.of("40"))
    }

    @Test
    fun `converges to constant on a constant series`() {
        val ema = EMA(5)
        repeat(20) { ema.update(Money.of("7")) }
        assertThat(ema.value()).isEqualByComparingTo(Money.of("7"))
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { EMA(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EMA(-1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
