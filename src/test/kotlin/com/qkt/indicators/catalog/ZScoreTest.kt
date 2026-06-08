package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ZScoreTest {
    @Test
    fun `not ready before warmup`() {
        val z = ZScore(3)
        z.update(Money.of("10"))
        z.update(Money.of("20"))
        assertThat(z.isReady).isFalse()
        assertThat(z.value()).isNull()
        assertThat(z.warmupBars).isEqualTo(3)
    }

    @Test
    fun `null on a flat series`() {
        // Zero stddev → z-score is undefined; the contract returns null, not zero.
        val z = ZScore(5)
        repeat(5) { z.update(Money.of("7")) }
        assertThat(z.isReady).isTrue()
        assertThat(z.value()).isNull()
    }

    @Test
    fun `matches the textbook z-score on a known series`() {
        // Values 2,4,4,4,5,5,7,9 → mean 5, sample stddev sqrt(32/7) ≈ 2.138089935.
        // z of the latest (9): (9 - 5) / 2.138089935 ≈ 1.870828693.
        val z = ZScore(8)
        listOf("2", "4", "4", "4", "5", "5", "7", "9").forEach { z.update(Money.of(it)) }
        assertThat(z.value()!!.toDouble()).isCloseTo(1.870828693, within(1e-6))
    }

    @Test
    fun `negative when the latest value sits below the mean`() {
        // Window 1,2,3,4,5 → mean 3, sample stddev ≈ 1.5811. Feed a low latest by rolling.
        val z = ZScore(5)
        listOf("5", "4", "3", "2", "1").forEach { z.update(Money.of(it)) }
        assertThat(z.value()!!.toDouble()).isLessThan(0.0)
    }

    @Test
    fun `rolls window after N values`() {
        val z = ZScore(3)
        // 1,2,3 → mean 2, stddev 1, latest 3 → z = 1.0
        listOf("1", "2", "3").forEach { z.update(Money.of(it)) }
        assertThat(z.value()!!.toDouble()).isCloseTo(1.0, within(1e-7))
        // Add 2 → window 2,3,2 → mean 2.333, stddev ≈ 0.5774, latest 2 → z ≈ -0.5774
        z.update(Money.of("2"))
        assertThat(z.value()!!.toDouble()).isCloseTo(-0.57735, within(1e-4))
    }

    @Test
    fun `rejects period below 2`() {
        assertThatThrownBy { ZScore(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ZScore(1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
