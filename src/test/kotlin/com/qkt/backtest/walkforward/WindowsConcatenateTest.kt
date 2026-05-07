package com.qkt.backtest.walkforward

import com.qkt.backtest.EquitySample
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WindowsConcatenateTest {
    @Test
    fun `empty list returns empty`() {
        assertThat(concatenate(emptyList<List<EquitySample>>())).isEmpty()
    }

    @Test
    fun `single curve passes through unchanged`() {
        val curve =
            listOf(
                EquitySample(0L, BigDecimal("0")),
                EquitySample(1L, BigDecimal("10")),
                EquitySample(2L, BigDecimal("15")),
            )
        val out = concatenate(listOf(curve))
        assertThat(out).hasSize(3)
        assertThat(out[2].equity).isEqualByComparingTo(BigDecimal("15"))
    }

    @Test
    fun `multi-fold cumulative offsets each fold by prior total`() {
        val foldA =
            listOf(
                EquitySample(0L, BigDecimal("0")),
                EquitySample(1L, BigDecimal("10")),
            )
        val foldB =
            listOf(
                EquitySample(2L, BigDecimal("0")),
                EquitySample(3L, BigDecimal("5")),
            )
        val out = concatenate(listOf(foldA, foldB))
        assertThat(out).hasSize(4)
        assertThat(out[0].equity).isEqualByComparingTo(BigDecimal("0"))
        assertThat(out[1].equity).isEqualByComparingTo(BigDecimal("10"))
        assertThat(out[2].equity).isEqualByComparingTo(BigDecimal("10"))
        assertThat(out[3].equity).isEqualByComparingTo(BigDecimal("15"))
    }

    @Test
    fun `empty fold in middle does not affect offset`() {
        val foldA =
            listOf(
                EquitySample(0L, BigDecimal("0")),
                EquitySample(1L, BigDecimal("10")),
            )
        val foldEmpty = emptyList<EquitySample>()
        val foldC =
            listOf(
                EquitySample(2L, BigDecimal("0")),
                EquitySample(3L, BigDecimal("3")),
            )
        val out = concatenate(listOf(foldA, foldEmpty, foldC))
        assertThat(out).hasSize(4)
        assertThat(out.last().equity).isEqualByComparingTo(BigDecimal("13"))
    }
}
