package com.qkt.dsl.compile

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BracketPercentTest {
    @Test
    fun `converts whole percentage points to fractions`() {
        assertThat(BracketPercent.fraction(BigDecimal("0.4"), isStopLoss = true))
            .isEqualByComparingTo("0.004")
        assertThat(BracketPercent.fraction(BigDecimal("1"), isStopLoss = false))
            .isEqualByComparingTo("0.01")
    }

    @Test
    fun `validates stop percentages at the shared boundary`() {
        assertThatThrownBy { BracketPercent.fraction(BigDecimal.ZERO, isStopLoss = true) }
            .hasMessageContaining("greater than 0")
        assertThatThrownBy { BracketPercent.fraction(BigDecimal("0.004"), isStopLoss = true) }
            .hasMessageContaining("minimum 0.01 percentage points")
            .hasMessageContaining("0.4 means 0.4%")
        assertThatThrownBy { BracketPercent.fraction(BigDecimal("50"), isStopLoss = true) }
            .hasMessageContaining("less than 50")
    }

    @Test
    fun `accepts one basis point and rejects smaller take profit distances`() {
        assertThat(BracketPercent.fraction(BigDecimal("0.01"), isStopLoss = true))
            .isEqualByComparingTo("0.0001")
        assertThatThrownBy { BracketPercent.fraction(BigDecimal("0.009"), isStopLoss = false) }
            .hasMessageContaining("minimum 0.01 percentage points")
    }
}
