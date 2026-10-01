package com.qkt.derivatives.options.pricing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class NormalDistributionTest {
    @Test
    fun `the cdf matches published table values`() {
        assertThat(NormalDistribution.cdf(0.0)).isCloseTo(0.5, within(1e-7))
        assertThat(NormalDistribution.cdf(1.96)).isCloseTo(0.9750021, within(1e-7))
        assertThat(NormalDistribution.cdf(-1.0)).isCloseTo(0.1586553, within(1e-7))
        assertThat(NormalDistribution.cdf(0.7693)).isCloseTo(0.7791, within(1e-4))
    }

    @Test
    fun `the cdf is symmetric and stays in the unit interval far in the tails`() {
        for (x in listOf(0.1, 0.5, 1.3, 2.7, 4.0)) {
            assertThat(NormalDistribution.cdf(x) + NormalDistribution.cdf(-x)).isCloseTo(1.0, within(1e-12))
        }
        assertThat(NormalDistribution.cdf(10.0)).isBetween(0.0, 1.0).isCloseTo(1.0, within(1e-12))
        assertThat(NormalDistribution.cdf(-10.0)).isBetween(0.0, 1.0).isCloseTo(0.0, within(1e-12))
    }

    @Test
    fun `the pdf is the standard normal density`() {
        assertThat(NormalDistribution.pdf(0.0)).isCloseTo(0.3989422804, within(1e-10))
        assertThat(NormalDistribution.pdf(1.0)).isCloseTo(0.2419707245, within(1e-10))
        assertThat(NormalDistribution.pdf(-1.0)).isEqualTo(NormalDistribution.pdf(1.0))
    }

    @Test
    fun `the far tails keep their relative precision`() {
        // Exact values from 0.5 * erfc(-x / sqrt 2).
        val exact = mapOf(-5.0 to 2.866515718791946e-7, -8.0 to 6.220960574271819e-16, -20.0 to 2.7536241186063314e-89)
        for ((x, value) in exact) {
            assertThat(NormalDistribution.cdf(x) / value).describedAs("x=$x").isCloseTo(1.0, within(1e-8))
        }
    }
}
