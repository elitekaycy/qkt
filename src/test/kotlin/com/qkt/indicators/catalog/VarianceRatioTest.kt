package com.qkt.indicators.catalog

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class VarianceRatioTest {
    private fun feed(
        ind: VarianceRatio,
        vararg prices: String,
    ) = prices.forEach { ind.update(BigDecimal(it)) }

    @Test
    fun `null before lookback plus one prices`() {
        val vr = VarianceRatio(k = 2, lookback = 4)
        // 4 returns need 5 prices; 4 prices is one short.
        feed(vr, "100", "110", "99", "108.9")
        assertThat(vr.isReady).isFalse()
        assertThat(vr.value()).isNull()
        assertThat(vr.warmupBars).isEqualTo(5)
    }

    @Test
    fun `perfectly alternating returns give a fully mean-reverting ratio of zero`() {
        val vr = VarianceRatio(k = 2, lookback = 4)
        // returns +0.1, -0.1, +0.1, -0.1 → every 2-bar sum is 0 → VR 0.
        feed(vr, "100", "110", "99", "108.9", "98.01")
        assertThat(vr.isReady).isTrue()
        assertThat(vr.value()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `persistent then reversing returns give a trending ratio above one`() {
        val vr = VarianceRatio(k = 2, lookback = 4)
        // returns +0.1, +0.1, -0.1, -0.1 → 2-bar sums 0.2, 0, -0.2 → VR = (0.08/3)/0.02 = 4/3.
        feed(vr, "100", "110", "121", "108.9", "98.01")
        assertThat(vr.value()!!.toDouble()).isCloseTo(1.33333333, within(1e-8))
    }

    @Test
    fun `flat prices have zero one-bar variance and report null`() {
        val vr = VarianceRatio(k = 2, lookback = 4)
        feed(vr, "100", "100", "100", "100", "100")
        assertThat(vr.isReady).isTrue()
        assertThat(vr.value()).isNull()
    }

    @Test
    fun `rejects k below two`() {
        assertThatThrownBy { VarianceRatio(k = 1, lookback = 4) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects lookback not greater than k`() {
        assertThatThrownBy { VarianceRatio(k = 4, lookback = 4) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
