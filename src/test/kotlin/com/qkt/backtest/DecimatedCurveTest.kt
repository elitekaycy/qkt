package com.qkt.backtest

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DecimatedCurveTest {
    private fun sample(t: Long) = EquitySample(t, BigDecimal(t))

    @Test
    fun `under cap retains every sample`() {
        val c = DecimatedCurve(cap = 100)
        (0 until 50).forEach { c.accept(sample(it.toLong())) }
        assertThat(c.snapshot()).hasSize(50)
        assertThat(c.snapshot().map { it.timestamp }).isEqualTo((0L until 50L).toList())
    }

    @Test
    fun `over cap stays bounded`() {
        val cap = 1_000
        val c = DecimatedCurve(cap = cap)
        (0 until 1_000_000).forEach { c.accept(sample(it.toLong())) }
        // Bounded by cap (+1 for the appended trailing sample).
        assertThat(c.snapshot().size).isLessThanOrEqualTo(cap + 1)
        assertThat(c.snapshot().size).isGreaterThan(cap / 4)
    }

    @Test
    fun `preserves first and last samples`() {
        val c = DecimatedCurve(cap = 8)
        (0..1000L).forEach { c.accept(sample(it)) }
        val snap = c.snapshot()
        assertThat(snap.first().timestamp).isEqualTo(0L)
        assertThat(snap.last().timestamp).isEqualTo(1000L)
    }

    @Test
    fun `snapshot stays in timestamp order`() {
        val c = DecimatedCurve(cap = 16)
        (0..5000L).forEach { c.accept(sample(it)) }
        val ts = c.snapshot().map { it.timestamp }
        assertThat(ts).isEqualTo(ts.sorted())
    }

    @Test
    fun `empty curve yields empty snapshot`() {
        assertThat(DecimatedCurve(cap = 4).snapshot()).isEmpty()
    }

    @Test
    fun `single sample is its own snapshot`() {
        val c = DecimatedCurve(cap = 4)
        c.accept(sample(7L))
        assertThat(c.snapshot().map { it.timestamp }).isEqualTo(listOf(7L))
    }

    @Test
    fun `cap below two is rejected`() {
        assertThatThrownBy { DecimatedCurve(cap = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
