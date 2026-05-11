package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RollingLowTest {
    @Test
    fun `not ready before warmup`() {
        val r = RollingLow(3)
        r.update(Money.of("10"))
        r.update(Money.of("20"))
        assertThat(r.isReady).isFalse()
        assertThat(r.value()).isNull()
        assertThat(r.warmupBars).isEqualTo(3)
    }

    @Test
    fun `min of last N values`() {
        val r = RollingLow(3)
        listOf("30", "20", "25").forEach { r.update(Money.of(it)) }
        assertThat(r.isReady).isTrue()
        assertThat(r.value()).isEqualByComparingTo(Money.of("20"))
    }

    @Test
    fun `window slides — older values drop out`() {
        val r = RollingLow(3)
        listOf("30", "20", "25").forEach { r.update(Money.of(it)) }
        assertThat(r.value()).isEqualByComparingTo(Money.of("20"))
        r.update(Money.of("15"))
        assertThat(r.value()).isEqualByComparingTo(Money.of("15"))
        r.update(Money.of("22"))
        assertThat(r.value()).isEqualByComparingTo(Money.of("15"))
        r.update(Money.of("28"))
        assertThat(r.value()).isEqualByComparingTo(Money.of("15"))
        r.update(Money.of("35"))
        assertThat(r.value()).isEqualByComparingTo(Money.of("22"))
    }

    @Test
    fun `negative or zero period rejected`() {
        assertThatThrownBy { RollingLow(0) }.hasMessageContaining("period must be > 0")
    }
}
