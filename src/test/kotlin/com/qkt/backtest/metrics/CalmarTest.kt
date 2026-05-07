package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CalmarTest {
    @Test
    fun `zero drawdown returns null`() {
        assertThat(calmar(BigDecimal("100"), Money.ZERO)).isNull()
    }

    @Test
    fun `positive return divided by drawdown`() {
        assertThat(calmar(BigDecimal("100"), BigDecimal("0.2")))
            .isEqualByComparingTo(BigDecimal("500"))
    }

    @Test
    fun `negative return divided by drawdown`() {
        assertThat(calmar(BigDecimal("-50"), BigDecimal("0.5")))
            .isEqualByComparingTo(BigDecimal("-100"))
    }
}
