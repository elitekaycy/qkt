package com.qkt.indicators.catalog

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RSITest {
    @Test
    fun `not ready before N plus 1 closes`() {
        val rsi = RSI(2)
        rsi.update(Money.of("10"))
        rsi.update(Money.of("12"))
        assertThat(rsi.isReady).isFalse()
        assertThat(rsi.value()).isNull()
        assertThat(rsi.warmupBars).isEqualTo(3)
    }

    @Test
    fun `mixed gains and losses produce expected RSI`() {
        val rsi = RSI(2)
        listOf("10", "12", "11").forEach { rsi.update(Money.of(it)) }
        assertThat(rsi.value()).isEqualByComparingTo(BigDecimal("66.66666667"))
    }

    @Test
    fun `monotonic up series yields RSI 100`() {
        val rsi = RSI(2)
        listOf("10", "11", "12").forEach { rsi.update(Money.of(it)) }
        assertThat(rsi.value()).isEqualByComparingTo(Money.of("100"))
    }

    @Test
    fun `monotonic down series yields RSI 0`() {
        val rsi = RSI(2)
        listOf("10", "9", "8").forEach { rsi.update(Money.of(it)) }
        assertThat(rsi.value()).isEqualByComparingTo(Money.of("0"))
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { RSI(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RSI(-1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
