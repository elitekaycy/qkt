package com.qkt.backtest.metrics

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SharpeTest {
    @Test
    fun `fewer than two samples returns null`() {
        assertThat(sharpe(emptyList(), BigDecimal("525960"))).isNull()
        assertThat(sharpe(listOf(BigDecimal("100")), BigDecimal("525960"))).isNull()
    }

    @Test
    fun `constant equity returns null due to zero stddev`() {
        val curve = listOf(BigDecimal("100"), BigDecimal("100"), BigDecimal("100"))
        assertThat(sharpe(curve, BigDecimal("525960"))).isNull()
    }

    @Test
    fun `monotonic up gives positive sharpe`() {
        val curve = listOf(BigDecimal("100"), BigDecimal("110"), BigDecimal("120"), BigDecimal("130"))
        val s = sharpe(curve, BigDecimal("252"))!!
        assertThat(s.signum()).isEqualTo(1)
    }

    @Test
    fun `oscillating curve gives finite sharpe`() {
        val curve = listOf(BigDecimal("100"), BigDecimal("110"), BigDecimal("100"), BigDecimal("110"))
        val s = sharpe(curve, BigDecimal("252"))
        assertThat(s).isNotNull()
    }
}
