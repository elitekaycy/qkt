package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TEMATest {
    @Test
    fun `not ready before warmup`() {
        val t = TEMA(5)
        repeat(10) { t.update(Money.of("10")) }
        assertThat(t.warmupBars).isEqualTo(13)
        assertThat(t.isReady).isFalse()
        assertThat(t.value()).isNull()
    }

    @Test
    fun `settles to the constant on a flat series`() {
        // 3c - 3c + c = c exactly when every EMA equals the constant.
        val t = TEMA(5)
        repeat(30) { t.update(Money.of("42")) }
        assertThat(t.value()).isEqualByComparingTo(Money.of("42"))
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { TEMA(0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
