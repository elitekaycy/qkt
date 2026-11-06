package com.qkt.risk.rules

import com.qkt.risk.DailyDrawdownBasis
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DrawdownRuleValidationTest {
    private val tooBig = BigDecimal("1.01")

    @Test
    fun `account drawdown halts refuse a fraction outside zero to one`() {
        assertThatThrownBy { MaxDrawdown(tooBig) }.hasMessage("maxFraction must be in (0, 1]: 1.01")
        assertThatThrownBy { MaxDailyDrawdown(BigDecimal.ZERO) }.hasMessage("maxFraction must be in (0, 1]: 0")
    }

    @Test
    fun `strategy drawdown halts refuse a fraction outside zero to one`() {
        assertThatThrownBy { MaxStrategyDrawdown("s", tooBig) }.hasMessage("maxFraction must be in (0, 1]: 1.01")
        assertThatThrownBy { MaxStrategyDailyDrawdown("s", BigDecimal("-0.1")) }
            .hasMessage("maxFraction must be in (0, 1]: -0.1")
    }

    @Test
    fun `the daily drawdown basis reads balance or equity and refuses anything else`() {
        assertThat(DailyDrawdownBasis.fromConfig(null)).isEqualTo(DailyDrawdownBasis.BALANCE)
        assertThat(DailyDrawdownBasis.fromConfig("EQUITY")).isEqualTo(DailyDrawdownBasis.EQUITY)
        assertThatThrownBy { DailyDrawdownBasis.fromConfig("weekly") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("unknown daily_dd_basis 'weekly' (valid: balance, equity)")
    }
}
