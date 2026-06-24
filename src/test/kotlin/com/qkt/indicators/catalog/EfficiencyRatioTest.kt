package com.qkt.indicators.catalog

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class EfficiencyRatioTest {
    private fun feed(
        ind: EfficiencyRatio,
        vararg prices: String,
    ) = prices.forEach { ind.update(BigDecimal(it)) }

    @Test
    fun `null before warmup`() {
        val er = EfficiencyRatio(period = 3)
        // period counts steps, so 3 steps need 4 prices; 3 prices is one short.
        feed(er, "100", "101", "102")
        assertThat(er.isReady).isFalse()
        assertThat(er.value()).isNull()
        assertThat(er.warmupBars).isEqualTo(4)
    }

    @Test
    fun `perfectly efficient uptrend is one`() {
        val er = EfficiencyRatio(period = 3)
        // net 3, path 1+1+1 = 3 → 1.0.
        feed(er, "100", "101", "102", "103")
        assertThat(er.isReady).isTrue()
        assertThat(er.value()).isEqualByComparingTo("1")
    }

    @Test
    fun `perfectly efficient downtrend is one`() {
        val er = EfficiencyRatio(period = 3)
        feed(er, "103", "102", "101", "100")
        assertThat(er.value()).isEqualByComparingTo("1")
    }

    @Test
    fun `whipsaw that ends where it swung is inefficient`() {
        val er = EfficiencyRatio(period = 3)
        // net |102-100| = 2, path 2+2+2 = 6 → 0.3333.
        feed(er, "100", "102", "100", "102")
        assertThat(er.value()!!.toDouble()).isCloseTo(1.0 / 3.0, within(1e-7))
    }

    @Test
    fun `flat window with no movement is zero`() {
        val er = EfficiencyRatio(period = 3)
        feed(er, "100", "100", "100", "100")
        assertThat(er.value()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `window rolls forward dropping the oldest price`() {
        val er = EfficiencyRatio(period = 3)
        // Leading 50 leaves the window after the 5th price, so the clean 100..103
        // uptrend remains → 1.0, proving the stale jump 50->100 is excluded.
        feed(er, "50", "100", "101", "102", "103")
        assertThat(er.value()).isEqualByComparingTo("1")
    }

    @Test
    fun `rejects period below two`() {
        assertThatThrownBy { EfficiencyRatio(period = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
