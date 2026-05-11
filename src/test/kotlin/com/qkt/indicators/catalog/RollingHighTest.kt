package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RollingHighTest {
    @Test
    fun `not ready before warmup`() {
        val r = RollingHigh(3)
        r.update(Money.of("10"))
        r.update(Money.of("20"))
        assertThat(r.isReady).isFalse()
        assertThat(r.value()).isNull()
        assertThat(r.warmupBars).isEqualTo(3)
    }

    @Test
    fun `max of last N values`() {
        val r = RollingHigh(3)
        listOf("10", "20", "15").forEach { r.update(Money.of(it)) }
        assertThat(r.isReady).isTrue()
        assertThat(r.value()).isEqualByComparingTo(Money.of("20"))
    }

    @Test
    fun `window slides — older values drop out`() {
        val r = RollingHigh(3)
        listOf("10", "20", "15").forEach { r.update(Money.of(it)) }
        assertThat(r.value()).isEqualByComparingTo(Money.of("20"))
        r.update(Money.of("25"))
        assertThat(r.value()).isEqualByComparingTo(Money.of("25"))
        r.update(Money.of("18"))
        assertThat(r.value()).isEqualByComparingTo(Money.of("25"))
        r.update(Money.of("12"))
        assertThat(r.value()).isEqualByComparingTo(Money.of("25"))
        r.update(Money.of("8"))
        assertThat(r.value()).isEqualByComparingTo(Money.of("18"))
    }

    @Test
    fun `single-period window returns latest value`() {
        val r = RollingHigh(1)
        r.update(Money.of("100"))
        assertThat(r.isReady).isTrue()
        assertThat(r.value()).isEqualByComparingTo(Money.of("100"))
        r.update(Money.of("50"))
        assertThat(r.value()).isEqualByComparingTo(Money.of("50"))
    }

    @Test
    fun `negative or zero period rejected`() {
        assertThatThrownBy { RollingHigh(0) }.hasMessageContaining("period must be > 0")
        assertThatThrownBy { RollingHigh(-1) }.hasMessageContaining("period must be > 0")
    }
}
