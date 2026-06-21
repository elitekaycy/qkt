package com.qkt.indicators.catalog

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PercentileRankTest {
    private fun feed(
        ind: PercentileRank,
        vararg values: String,
    ) = values.forEach { ind.update(BigDecimal(it)) }

    @Test
    fun `null before warmup`() {
        val pr = PercentileRank(period = 5)
        feed(pr, "1", "2", "3", "4")
        assertThat(pr.isReady).isFalse()
        assertThat(pr.value()).isNull()
    }

    @Test
    fun `ranks current value as fraction of window strictly below it`() {
        val pr = PercentileRank(period = 5)
        feed(pr, "10", "20", "30", "40", "25")
        // window [10,20,30,40,25], current 25; below = {10,20} = 2 of 5.
        assertThat(pr.value()).isEqualByComparingTo("0.4")
    }

    @Test
    fun `lowest current value ranks zero`() {
        val pr = PercentileRank(period = 5)
        feed(pr, "50", "40", "30", "20", "10")
        assertThat(pr.value()).isEqualByComparingTo("0")
    }

    @Test
    fun `highest current value ranks (n-1) over n`() {
        val pr = PercentileRank(period = 5)
        feed(pr, "10", "20", "30", "40", "50")
        assertThat(pr.value()).isEqualByComparingTo("0.8")
    }

    @Test
    fun `ties are not counted as below`() {
        val pr = PercentileRank(period = 5)
        feed(pr, "10", "20", "20", "20", "20")
        // current 20; strictly below = {10} = 1 of 5.
        assertThat(pr.value()).isEqualByComparingTo("0.2")
    }

    @Test
    fun `window rolls forward dropping the oldest`() {
        val pr = PercentileRank(period = 5)
        feed(pr, "1", "2", "3", "4", "5", "6")
        // window [2,3,4,5,6], current 6; below = 4 of 5.
        assertThat(pr.value()).isEqualByComparingTo("0.8")
    }

    @Test
    fun `rejects period below two`() {
        assertThatThrownBy { PercentileRank(period = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
