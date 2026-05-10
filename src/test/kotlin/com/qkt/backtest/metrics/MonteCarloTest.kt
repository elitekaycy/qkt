package com.qkt.backtest.metrics

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MonteCarloTest {
    @Test
    fun `fixed seed produces deterministic percentiles`() {
        val returns = listOf("1", "-0.5", "2", "-1", "0.5", "3", "-2", "1.5").map { BigDecimal(it) }
        val a = MonteCarlo.run(returns, BigDecimal("100"), simulations = 500, seed = 42L)
        val b = MonteCarlo.run(returns, BigDecimal("100"), simulations = 500, seed = 42L)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `all-positive returns yield non-negative final equity`() {
        val returns = listOf("1", "2", "3").map { BigDecimal(it) }
        val s = MonteCarlo.run(returns, BigDecimal("100"), simulations = 200, seed = 1L)
        assertThat(s.finalEquityP5).isGreaterThanOrEqualTo(BigDecimal("100"))
        assertThat(s.probabilityNegativeFinal).isEqualByComparingTo("0")
    }

    @Test
    fun `all-negative returns produce final-equity bias below start`() {
        val returns = listOf("-1", "-2", "-3").map { BigDecimal(it) }
        val s = MonteCarlo.run(returns, BigDecimal("100"), simulations = 200, seed = 1L)
        assertThat(s.finalEquityP95).isLessThan(BigDecimal("100"))
    }

    @Test
    fun `equity fan length matches trade count`() {
        val returns = listOf("1", "-1", "2", "-2", "0.5").map { BigDecimal(it) }
        val s = MonteCarlo.run(returns, BigDecimal("100"), simulations = 100, seed = 7L)
        assertThat(s.equityFanByTradeIndex).hasSize(returns.size)
    }

    @Test
    fun `percentiles are ordered`() {
        val returns =
            listOf("1", "-1", "2", "-2", "0.5", "0.5", "-0.5", "1.5", "-1.5", "0.1")
                .map { BigDecimal(it) }
        val s = MonteCarlo.run(returns, BigDecimal("100"), simulations = 500, seed = 42L)
        assertThat(s.finalEquityP5).isLessThanOrEqualTo(s.finalEquityP25)
        assertThat(s.finalEquityP25).isLessThanOrEqualTo(s.finalEquityP50)
        assertThat(s.finalEquityP50).isLessThanOrEqualTo(s.finalEquityP75)
        assertThat(s.finalEquityP75).isLessThanOrEqualTo(s.finalEquityP95)
    }
}
