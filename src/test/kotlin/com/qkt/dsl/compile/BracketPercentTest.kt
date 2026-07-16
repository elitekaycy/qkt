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
        assertThatThrownBy { BracketPercent.fraction(BigDecimal("50"), isStopLoss = true) }
            .hasMessageContaining("less than 50")
    }
}
