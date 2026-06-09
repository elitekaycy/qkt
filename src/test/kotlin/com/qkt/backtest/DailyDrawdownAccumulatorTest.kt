package com.qkt.backtest

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DailyDrawdownAccumulatorTest {
    private val day = 86_400_000L

    @Test
    fun `worst intraday decline from day-open across days`() {
        val acc = DailyDrawdownAccumulator()
        // Day 0: opens 100, dips to 90 => 10% intraday.
        acc.accept(0L, BigDecimal("100"))
        acc.accept(1_000L, BigDecimal("90"))
        // Day 1: opens 90, dips to 81 (10%), recovers to 95.
        acc.accept(day, BigDecimal("90"))
        acc.accept(day + 1_000L, BigDecimal("81"))
        acc.accept(day + 2_000L, BigDecimal("95"))
        assertThat(acc.maxDailyDrawdown()).isEqualByComparingTo(BigDecimal("0.10"))
    }

    @Test
    fun `worst day wins when one day is steeper`() {
        val acc = DailyDrawdownAccumulator()
        acc.accept(0L, BigDecimal("100"))
        acc.accept(1_000L, BigDecimal("95")) // 5%
        acc.accept(day, BigDecimal("100"))
        acc.accept(day + 1_000L, BigDecimal("80")) // 20%
        assertThat(acc.maxDailyDrawdown()).isEqualByComparingTo(BigDecimal("0.20"))
    }

    @Test
    fun `zero when equity only rises`() {
        val acc = DailyDrawdownAccumulator()
        acc.accept(0L, BigDecimal("100"))
        acc.accept(1_000L, BigDecimal("110"))
        assertThat(acc.maxDailyDrawdown()).isEqualByComparingTo(BigDecimal.ZERO)
    }
}
