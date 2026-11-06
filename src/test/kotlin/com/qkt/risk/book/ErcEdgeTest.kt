package com.qkt.risk.book

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ErcEdgeTest {
    private fun diagonal(vararg variances: Pair<String, String>): (String, String) -> BigDecimal {
        val v = variances.toMap()
        return { i, j -> if (i == j) BigDecimal(v.getValue(i)) else BigDecimal.ZERO }
    }

    @Test
    fun `no ids give no weights`() {
        assertThat(equalWeights(emptyList())).isEmpty()
        assertThat(inverseVol(emptyMap())).isEmpty()
        assertThat(erc(emptyList(), diagonal())).isEmpty()
    }

    @Test
    fun `a single strategy takes the whole book`() {
        assertThat(erc(listOf("a"), diagonal("a" to "0.04"))).isEqualTo(mapOf("a" to BigDecimal.ONE))
    }

    @Test
    fun `inverse vol with no positive variance falls back to equal weights`() {
        val w = inverseVol(mapOf("a" to BigDecimal.ZERO, "b" to BigDecimal("-1")))

        assertThat(w.getValue("a")).isEqualByComparingTo("0.5")
        assertThat(w.getValue("b")).isEqualByComparingTo("0.5")
    }

    @Test
    fun `inverse vol gives a zero-variance strategy nothing beside a risky one`() {
        val w = inverseVol(mapOf("flat" to BigDecimal.ZERO, "risky" to BigDecimal("0.04")))

        assertThat(w.getValue("flat")).isEqualByComparingTo("0")
        assertThat(w.getValue("risky")).isEqualByComparingTo("1")
    }

    @Test
    fun `erc on a degenerate matrix falls back to inverse vol`() {
        val w = erc(listOf("a", "b"), diagonal("a" to "0", "b" to "0.04"))

        assertThat(w.getValue("a")).isEqualByComparingTo("0")
        assertThat(w.getValue("b")).isEqualByComparingTo("1")
    }

    @Test
    fun `erc falls back to inverse vol when the portfolio variance is not positive`() {
        // Perfectly anti-correlated beyond the diagonal: total risk contribution goes negative.
        val cov = { i: String, j: String -> if (i == j) BigDecimal("0.01") else BigDecimal("-0.02") }

        val w = erc(listOf("a", "b"), cov)

        assertThat(w.getValue("a")).isEqualByComparingTo("0.5")
        assertThat(w.getValue("b")).isEqualByComparingTo("0.5")
    }

    @Test
    fun `vol target leaves weights untouched when the book has no vol`() {
        val weights = mapOf("a" to BigDecimal("0.5"), "b" to BigDecimal("0.5"))

        val none = { _: String, _: String -> BigDecimal.ZERO }
        val some = { _: String, _: String -> BigDecimal("0.01") }

        assertThat(volTarget(weights, listOf("a", "b"), none, BigDecimal("252"), BigDecimal("0.1"), BigDecimal("4")))
            .isEqualTo(weights)
        assertThat(volTarget(weights, listOf("a", "b"), some, BigDecimal.ZERO, BigDecimal("0.1"), BigDecimal("4")))
            .isEqualTo(weights)
    }

    @Test
    fun `vol target scales without the cap when gross stays under it`() {
        val weights = mapOf("a" to BigDecimal("1"))

        val cov = { _: String, _: String -> BigDecimal("0.01") }

        val out = volTarget(weights, listOf("a"), cov, BigDecimal.ONE, BigDecimal("0.2"), BigDecimal("4"))

        // vol 0.1, target 0.2: scale 2.
        assertThat(out.getValue("a")).isEqualByComparingTo("2")
    }
}
