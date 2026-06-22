package com.qkt.backtest.metrics

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SortinoTest {
    private val daily = BigDecimal("252")

    @Test
    fun `flat or rising equity has no downside so sortino is null`() {
        assertThat(sortino(listOf("100", "110", "120").map(::BigDecimal), daily)).isNull()
    }

    @Test
    fun `fewer than two readings is null`() {
        assertThat(sortino(listOf(BigDecimal("100")), daily)).isNull()
    }

    @Test
    fun `a drawdown produces a finite sortino and it differs from sharpe`() {
        val curve = listOf("100", "120", "90", "130").map(::BigDecimal)
        val s = sortino(curve, daily)
        assertThat(s).isNotNull()
        // Only the 120 -> 90 step is downside, so downside dev < full stddev => |sortino| > |sharpe|.
        assertThat(s!!.abs()).isGreaterThan(sharpe(curve, daily)!!.abs())
    }

    @Test
    fun `accumulator matches one-pass helper`() {
        val curve = listOf("100", "120", "90", "130").map(::BigDecimal)
        val acc = SortinoAccumulator()
        curve.forEach(acc::accept)
        assertThat(acc.value(daily)).isEqualTo(sortino(curve, daily))
    }
}
