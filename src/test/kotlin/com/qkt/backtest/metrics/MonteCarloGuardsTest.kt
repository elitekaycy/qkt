package com.qkt.backtest.metrics

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MonteCarloGuardsTest {
    @Test
    fun `no simulations or no trade returns are refused`() {
        assertThatThrownBy { MonteCarlo.run(listOf(BigDecimal.ONE), BigDecimal.TEN, simulations = 0, seed = 1L) }
            .hasMessage("simulations must be > 0: 0")
        assertThatThrownBy { MonteCarlo.run(emptyList(), BigDecimal.TEN, simulations = 5, seed = 1L) }
            .hasMessage("tradeReturns must not be empty")
    }

    @Test
    fun `from zero equity losses are all negative finals with no measurable drawdown`() {
        val summary = MonteCarlo.run(listOf(BigDecimal("-1"), BigDecimal("-2")), BigDecimal.ZERO, 20, seed = 7L)

        assertThat(summary.probabilityNegativeFinal).isEqualByComparingTo("1")
        assertThat(summary.maxDrawdownP5).isEqualByComparingTo("0")
        assertThat(summary.maxDrawdownP95).isEqualByComparingTo("0")
        assertThat(summary.finalEquityP95).isLessThan(BigDecimal.ZERO)
    }
}
