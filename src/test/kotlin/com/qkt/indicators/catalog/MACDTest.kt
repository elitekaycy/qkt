package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MACDTest {
    @Test
    fun `not ready until signal EMA seeds`() {
        val macd = MACD(fast = 2, slow = 3, signal = 2)
        listOf("2", "4", "6").forEach { macd.update(Money.of(it)) }
        assertThat(macd.isReady).isFalse()
        assertThat(macd.value()).isNull()
        assertThat(macd.warmupBars).isEqualTo(4)
    }

    @Test
    fun `linear ramp produces stable macd line`() {
        val macd = MACD(fast = 2, slow = 3, signal = 2)
        listOf("2", "4", "6", "8", "10").forEach { macd.update(Money.of(it)) }
        assertThat(macd.isReady).isTrue()
        assertThat(macd.value()).isEqualByComparingTo(Money.of("1"))
        val lines = macd.lines()!!
        assertThat(lines.macd).isEqualByComparingTo(Money.of("1"))
        assertThat(lines.signal).isEqualByComparingTo(Money.of("1"))
        assertThat(lines.histogram).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `constant series yields zero macd and zero histogram`() {
        val macd = MACD(fast = 2, slow = 3, signal = 2)
        repeat(10) { macd.update(Money.of("7")) }
        val lines = macd.lines()!!
        assertThat(lines.macd).isEqualByComparingTo(Money.ZERO)
        assertThat(lines.signal).isEqualByComparingTo(Money.ZERO)
        assertThat(lines.histogram).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `rejects invalid periods`() {
        assertThatThrownBy { MACD(fast = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MACD(fast = 12, slow = 12) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MACD(fast = 12, slow = 26, signal = 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
