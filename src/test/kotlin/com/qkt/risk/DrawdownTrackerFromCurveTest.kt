package com.qkt.risk

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DrawdownTrackerFromCurveTest {
    @Test
    fun `empty curve returns zero`() {
        assertThat(DrawdownTracker.fromCurve(emptyList())).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `monotone increasing curve has zero drawdown`() {
        val curve = listOf(BigDecimal("10"), BigDecimal("20"), BigDecimal("30"))
        assertThat(DrawdownTracker.fromCurve(curve)).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `peak then trough yields fractional drawdown`() {
        val curve = listOf(BigDecimal("0"), BigDecimal("100"), BigDecimal("60"), BigDecimal("80"))
        assertThat(DrawdownTracker.fromCurve(curve)).isEqualByComparingTo(BigDecimal("0.4"))
    }

    @Test
    fun `peak never positive returns zero`() {
        val curve = listOf(BigDecimal("-10"), BigDecimal("-20"), BigDecimal("-5"))
        assertThat(DrawdownTracker.fromCurve(curve)).isEqualByComparingTo(Money.ZERO)
    }
}
