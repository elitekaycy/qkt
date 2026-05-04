package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SMATest {
    @Test
    fun `not ready before warmup`() {
        val sma = SMA(3)
        sma.update(Money.of("10"))
        sma.update(Money.of("20"))
        assertThat(sma.isReady).isFalse()
        assertThat(sma.value()).isNull()
        assertThat(sma.warmupBars).isEqualTo(3)
    }

    @Test
    fun `mean of last N values`() {
        val sma = SMA(3)
        listOf("10", "20", "30").forEach { sma.update(Money.of(it)) }
        assertThat(sma.isReady).isTrue()
        assertThat(sma.value()).isEqualByComparingTo(Money.of("20"))
    }

    @Test
    fun `rolls window after N values`() {
        val sma = SMA(3)
        listOf("10", "20", "30", "40").forEach { sma.update(Money.of(it)) }
        assertThat(sma.value()).isEqualByComparingTo(Money.of("30"))
    }

    @Test
    fun `equals constant on a constant series`() {
        val sma = SMA(5)
        repeat(5) { sma.update(Money.of("7")) }
        assertThat(sma.value()).isEqualByComparingTo(Money.of("7"))
    }
}
