package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WMATest {
    @Test
    fun `not ready before warmup`() {
        val wma = WMA(3)
        wma.update(Money.of("3"))
        wma.update(Money.of("6"))
        assertThat(wma.isReady).isFalse()
        assertThat(wma.value()).isNull()
        assertThat(wma.warmupBars).isEqualTo(3)
    }

    @Test
    fun `linearly weighted mean`() {
        val wma = WMA(3)
        listOf("3", "6", "9").forEach { wma.update(Money.of(it)) }
        assertThat(wma.value()).isEqualByComparingTo(Money.of("7"))
    }

    @Test
    fun `equals constant on a constant series`() {
        val wma = WMA(4)
        repeat(4) { wma.update(Money.of("11")) }
        assertThat(wma.value()).isEqualByComparingTo(Money.of("11"))
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { WMA(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WMA(-1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
