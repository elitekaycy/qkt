package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProfitFactorTest {
    @Test
    fun `empty list returns null`() {
        assertThat(profitFactor(emptyList())).isNull()
    }

    @Test
    fun `all wins returns null since no losses`() {
        val realizeds = listOf(BigDecimal("10"), BigDecimal("5"))
        assertThat(profitFactor(realizeds)).isNull()
    }

    @Test
    fun `all losses returns zero`() {
        val realizeds = listOf(BigDecimal("-10"), BigDecimal("-5"))
        assertThat(profitFactor(realizeds)).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `mixed returns wins over abs losses`() {
        val realizeds = listOf(BigDecimal("10"), BigDecimal("20"), BigDecimal("-15"), BigDecimal("-5"))
        assertThat(profitFactor(realizeds)).isEqualByComparingTo(BigDecimal("1.5"))
    }

    @Test
    fun `zeros are ignored`() {
        val realizeds = listOf(BigDecimal("10"), BigDecimal("0"), BigDecimal("-5"))
        assertThat(profitFactor(realizeds)).isEqualByComparingTo(BigDecimal("2.0"))
    }
}
