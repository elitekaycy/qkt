package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WinLossStatsTest {
    @Test
    fun `empty list returns zeros`() {
        val stats = winLossStats(emptyList())
        assertThat(stats.avgWin).isEqualByComparingTo(Money.ZERO)
        assertThat(stats.avgLoss).isEqualByComparingTo(Money.ZERO)
        assertThat(stats.largestWin).isEqualByComparingTo(Money.ZERO)
        assertThat(stats.largestLoss).isEqualByComparingTo(Money.ZERO)
        assertThat(stats.maxConsecutiveLosses).isEqualTo(0)
    }

    @Test
    fun `mixed list computes avgs and extremes`() {
        val stats =
            winLossStats(
                listOf(
                    BigDecimal("10"),
                    BigDecimal("-5"),
                    BigDecimal("20"),
                    BigDecimal("-15"),
                    BigDecimal("30"),
                ),
            )
        assertThat(stats.avgWin).isEqualByComparingTo(BigDecimal("20"))
        assertThat(stats.avgLoss).isEqualByComparingTo(BigDecimal("-10"))
        assertThat(stats.largestWin).isEqualByComparingTo(BigDecimal("30"))
        assertThat(stats.largestLoss).isEqualByComparingTo(BigDecimal("-15"))
    }

    @Test
    fun `max consecutive losses counts longest negative run in order`() {
        val stats =
            winLossStats(
                listOf(
                    BigDecimal("10"),
                    BigDecimal("-1"),
                    BigDecimal("-2"),
                    BigDecimal("5"),
                    BigDecimal("-3"),
                    BigDecimal("-4"),
                    BigDecimal("-5"),
                    BigDecimal("8"),
                ),
            )
        assertThat(stats.maxConsecutiveLosses).isEqualTo(3)
    }

    @Test
    fun `zeros do not break consecutive loss counting`() {
        val stats = winLossStats(listOf(BigDecimal("-1"), BigDecimal("0"), BigDecimal("-2")))
        assertThat(stats.maxConsecutiveLosses).isEqualTo(1)
    }

    @Test
    fun `single win gives zero consecutive losses`() {
        val stats = winLossStats(listOf(BigDecimal("10")))
        assertThat(stats.maxConsecutiveLosses).isEqualTo(0)
    }
}
