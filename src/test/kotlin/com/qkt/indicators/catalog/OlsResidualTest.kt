package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class OlsResidualTest {
    /** Feed one [y, x1, …] tuple. */
    private fun OlsResidual.feed(vararg v: Number) = update(v.map { Money.of("$it") })

    @Test
    fun `not ready before warmup`() {
        val r = OlsResidual(period = 3, regressorCount = 1)
        r.feed(1, 1)
        r.feed(2, 2)
        assertThat(r.isReady).isFalse()
        assertThat(r.value()).isNull()
        assertThat(r.warmupBars).isEqualTo(3)
    }

    @Test
    fun `single-regressor residual matches the hand-computed value`() {
        // x = 1,2,3 ; y = 1,2,9 → OLS slope 4, intercept -4 → predict@3 = 8 → residual 9-8 = 1.
        val r = OlsResidual(period = 3, regressorCount = 1)
        r.feed(1, 1)
        r.feed(2, 2)
        r.feed(9, 3)
        assertThat(r.value()!!.toDouble()).isCloseTo(1.0, within(1e-7))
    }

    @Test
    fun `rolls the window and recomputes the latest residual`() {
        // After eviction the window is (y,x) = (2,1),(4,2),(9,3): slope 3.5, intercept -2,
        // predict@3 = 8.5 → residual 9-8.5 = 0.5.
        val r = OlsResidual(period = 3, regressorCount = 1)
        r.feed(0, 0)
        r.feed(2, 1)
        r.feed(4, 2)
        r.feed(9, 3)
        assertThat(r.value()!!.toDouble()).isCloseTo(0.5, within(1e-7))
    }

    @Test
    fun `perfectly linear data yields a zero residual`() {
        // y = 5 + 3x exactly → every residual is 0.
        val r = OlsResidual(period = 4, regressorCount = 1)
        (1..4).forEach { x -> r.feed(5 + 3 * x, x) }
        assertThat(r.value()!!.toDouble()).isCloseTo(0.0, within(1e-7))
    }

    @Test
    fun `positive residual when the latest point sits above the fitted line`() {
        // x = 1,2,3,4 ; y = 2,4,6,20 → slope 5.6, intercept -6, predict@4 = 16.4 → residual 3.6.
        val r = OlsResidual(period = 4, regressorCount = 1)
        listOf(2, 4, 6, 20).forEachIndexed { i, y -> r.feed(y, i + 1) }
        assertThat(r.value()!!.toDouble()).isCloseTo(3.6, within(1e-7))
    }

    @Test
    fun `two-regressor exact plane yields a zero residual`() {
        // y = 1 + 2*x1 + 3*x2 exactly over a non-collinear design → residual 0.
        val r = OlsResidual(period = 4, regressorCount = 2)
        listOf(
            Triple(1, 1, 6),
            Triple(2, 1, 8),
            Triple(1, 2, 9),
            Triple(3, 2, 13),
        ).forEach { (x1, x2, y) -> r.feed(y, x1, x2) }
        assertThat(r.value()!!.toDouble()).isCloseTo(0.0, within(1e-7))
    }

    @Test
    fun `null when regressors are collinear`() {
        // x2 = 2*x1 → the design matrix is singular, the fit is undefined.
        val r = OlsResidual(period = 4, regressorCount = 2)
        (1..4).forEach { x -> r.feed(x, x, 2 * x) }
        assertThat(r.isReady).isTrue()
        assertThat(r.value()).isNull()
    }

    @Test
    fun `null when a single regressor is constant`() {
        // A constant regressor is collinear with the intercept column.
        val r = OlsResidual(period = 4, regressorCount = 1)
        listOf(1, 4, 2, 7).forEach { y -> r.feed(y, 5) }
        assertThat(r.isReady).isTrue()
        assertThat(r.value()).isNull()
    }

    @Test
    fun `rejects degenerate construction`() {
        assertThatThrownBy { OlsResidual(period = 4, regressorCount = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        // period must exceed regressors + 1 to leave a degree of freedom.
        assertThatThrownBy { OlsResidual(period = 2, regressorCount = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a mis-sized tuple`() {
        val r = OlsResidual(period = 4, regressorCount = 2)
        assertThatThrownBy { r.feed(1, 2) } // expects 3 values, got 2
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
