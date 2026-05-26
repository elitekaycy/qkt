package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StddevTest {
    @Test
    fun `not ready before warmup`() {
        val s = Stddev(3)
        s.update(Money.of("10"))
        s.update(Money.of("20"))
        assertThat(s.isReady).isFalse()
        assertThat(s.value()).isNull()
        assertThat(s.warmupBars).isEqualTo(3)
    }

    @Test
    fun `zero on a constant series`() {
        val s = Stddev(5)
        repeat(5) { s.update(Money.of("7")) }
        assertThat(s.isReady).isTrue()
        assertThat(s.value()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `matches the textbook sample stddev formula on a known series`() {
        // Values: 2, 4, 4, 4, 5, 5, 7, 9 — textbook example.
        // Sample stddev (n-1) = sqrt(32/7) ≈ 2.13808993529...
        val s = Stddev(8)
        listOf("2", "4", "4", "4", "5", "5", "7", "9").forEach { s.update(Money.of(it)) }
        val v = s.value()!!
        assertThat(v.toDouble()).isCloseTo(
            2.13808993529,
            org.assertj.core.api.Assertions
                .within(1e-7),
        )
    }

    @Test
    fun `rolls window after N values`() {
        val s = Stddev(3)
        // First three: 1, 2, 3 → sample stddev = 1.0
        listOf("1", "2", "3").forEach { s.update(Money.of(it)) }
        assertThat(s.value()!!.toDouble()).isCloseTo(
            1.0,
            org.assertj.core.api.Assertions
                .within(1e-7),
        )
        // Add 4 → window holds 2, 3, 4. Sample stddev = 1.0 (same shape).
        s.update(Money.of("4"))
        assertThat(s.value()!!.toDouble()).isCloseTo(
            1.0,
            org.assertj.core.api.Assertions
                .within(1e-7),
        )
        // Add 100 → window holds 3, 4, 100. Mean ≈ 35.667; sample stddev ≈ 55.717.
        s.update(Money.of("100"))
        assertThat(s.value()!!.toDouble()).isCloseTo(
            55.71655,
            org.assertj.core.api.Assertions
                .within(1e-3),
        )
    }

    @Test
    fun `rejects period below 2`() {
        // Sample stddev needs n-1 in the divisor → period must be at least 2.
        assertThatThrownBy { Stddev(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Stddev(1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
