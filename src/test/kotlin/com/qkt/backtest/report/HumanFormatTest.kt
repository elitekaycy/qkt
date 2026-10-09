package com.qkt.backtest.report

import java.math.BigDecimal
import java.math.MathContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HumanFormatTest {
    @Test
    fun `money signs and groups`() {
        assertThat(HumanFormat.money(BigDecimal("4423.25"), "USD")).isEqualTo("+4,423.25 USD")
        assertThat(HumanFormat.money(BigDecimal("-12.5"), "USD")).isEqualTo("-12.50 USD")
        assertThat(HumanFormat.money(BigDecimal.ZERO, "USD")).isEqualTo("0.00 USD")
        assertThat(HumanFormat.money(BigDecimal("10000"), null, signed = false)).isEqualTo("10,000.00")
    }

    @Test
    fun `percent fractions with optional sign`() {
        assertThat(HumanFormat.percent(BigDecimal("0.43244333"))).isEqualTo("43.2%")
        assertThat(HumanFormat.percent(BigDecimal("0.308"))).isEqualTo("30.8%")
        assertThat(HumanFormat.percent(BigDecimal("0.0052"), signed = true)).isEqualTo("+0.5%")
        assertThat(HumanFormat.percent(BigDecimal("-0.011"))).isEqualTo("-1.1%")
    }

    @Test
    fun `tiny negative returns render as zero percent`() {
        val pct = BigDecimal("-2.78").divide(BigDecimal("10000"), MathContext.DECIMAL64)
        assertThat(HumanFormat.percent(pct, signed = true)).isEqualTo("0.0%")
    }

    @Test
    fun `ratios dates durations and axes`() {
        assertThat(HumanFormat.ratio(BigDecimal("1.2031"))).isEqualTo("1.20")
        assertThat(HumanFormat.ratio(null)).isEqualTo("n/a")
        assertThat(HumanFormat.utcDate(1_727_686_800_000L)).isEqualTo("2024-09-30 09:00 UTC")
        assertThat(HumanFormat.duration(16_613_100_000L)).isEqualTo("192 days")
        assertThat(HumanFormat.duration(3_600_000L)).isEqualTo("1 hour")
        assertThat(HumanFormat.axisMoney(BigDecimal("15110.004"))).isEqualTo("15,110")
    }
}
