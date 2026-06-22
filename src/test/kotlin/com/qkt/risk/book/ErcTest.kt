package com.qkt.risk.book

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ErcTest {
    @Test
    fun `inverse vol weights are proportional to one over sigma`() {
        // sigma 0.2 and 0.1 -> 1/sigma 5 and 10 -> normalized 1/3 and 2/3
        val w = inverseVol(mapOf("a" to BigDecimal("0.04"), "b" to BigDecimal("0.01")))
        assertThat(w.getValue("a")).isCloseTo(BigDecimal("0.3333"), within(BigDecimal("0.001")))
        assertThat(w.getValue("b")).isCloseTo(BigDecimal("0.6667"), within(BigDecimal("0.001")))
    }

    @Test
    fun `erc on an uncorrelated pair equals inverse vol`() {
        val cov = { i: String, j: String ->
            when {
                i != j -> BigDecimal.ZERO
                i == "a" -> BigDecimal("0.04")
                else -> BigDecimal("0.01")
            }
        }
        val w = erc(listOf("a", "b"), cov)
        assertThat(w.getValue("a")).isCloseTo(BigDecimal("0.3333"), within(BigDecimal("0.01")))
        assertThat(w.getValue("b")).isCloseTo(BigDecimal("0.6667"), within(BigDecimal("0.01")))
    }

    @Test
    fun `erc gives equal risk contributions`() {
        val cov = { i: String, j: String ->
            when {
                i == j && i == "a" -> BigDecimal("0.04")
                i == j -> BigDecimal("0.09")
                else -> BigDecimal("0.006") // correlation 0.1 between sigma .2 and .3
            }
        }
        val ids = listOf("a", "b")
        val w = erc(ids, cov)

        fun rc(k: String): BigDecimal {
            val mrc = ids.fold(BigDecimal.ZERO) { acc, j -> acc.add(cov(k, j).multiply(w.getValue(j))) }
            return w.getValue(k).multiply(mrc)
        }
        assertThat(rc("a")).isCloseTo(rc("b"), within(BigDecimal("0.001")))
    }

    @Test
    fun `vol target scales weights to hit the target vol`() {
        // single asset, weight 1, variance 0.0001/sample, annualization 252 -> vol ~0.1587
        val cov = { _: String, _: String -> BigDecimal("0.0001") }
        val scaled =
            volTarget(
                weights = mapOf("a" to BigDecimal.ONE),
                ids = listOf("a"),
                cov = cov,
                annualizationFactor = BigDecimal("252"),
                targetVol = BigDecimal("0.08"),
                maxLeverage = BigDecimal("10"),
            )
        // realized vol sqrt(0.0001*252)=0.1587; scale = 0.08/0.1587 ~ 0.504
        assertThat(scaled.getValue("a")).isCloseTo(BigDecimal("0.504"), within(BigDecimal("0.02")))
    }

    @Test
    fun `vol target respects the leverage cap`() {
        val cov = { _: String, _: String -> BigDecimal("0.0000001") } // tiny vol -> wants huge scale
        val scaled =
            volTarget(
                weights = mapOf("a" to BigDecimal.ONE),
                ids = listOf("a"),
                cov = cov,
                annualizationFactor = BigDecimal("252"),
                targetVol = BigDecimal("0.20"),
                maxLeverage = BigDecimal("3"),
            )
        assertThat(scaled.getValue("a")).isLessThanOrEqualTo(BigDecimal("3"))
    }
}
