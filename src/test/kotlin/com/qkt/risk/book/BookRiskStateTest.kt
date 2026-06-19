package com.qkt.risk.book

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookRiskStateTest {
    private fun state(
        gross: String,
        symbolNet: Map<String, String>,
        limits: BookLimits,
        capital: String = "10000",
    ) = BookRiskState(
        capital = BigDecimal(capital),
        grossExposure = BigDecimal(gross),
        perSymbolNet = symbolNet.mapValues { BigDecimal(it.value) },
        limits = limits,
    )

    @Test
    fun `gross cap rejects an order that would breach and allows one under`() {
        val s = state("25000", mapOf("X" to "25000"), BookLimits(maxGrossExposure = BigDecimal("3")))
        assertThat(s.limitBreach("X", BigDecimal("6000"))).isNotNull() // 31000 > 30000
        assertThat(s.limitBreach("X", BigDecimal("4000"))).isNull() // 29000 <= 30000
    }

    @Test
    fun `concentration cap on a single symbol`() {
        val s = state("0", mapOf("X" to "9000"), BookLimits(maxSymbolConcentration = BigDecimal("1")))
        assertThat(s.limitBreach("X", BigDecimal("2000"))).isNotNull() // 11000 > 10000
    }

    @Test
    fun `net cap nets opposing exposure across the book`() {
        val s = state("0", mapOf("X" to "5000", "Y" to "-4000"), BookLimits(maxNetExposure = BigDecimal("1")))
        // current net = 5000 + 4000 = 9000; adding -2000 to Y -> |X|5000 + |Y|6000 = 11000 > 10000
        assertThat(s.limitBreach("Y", BigDecimal("-2000"))).isNotNull()
    }

    @Test
    fun `null limits allow everything`() {
        val s = state("999999", mapOf("X" to "999999"), BookLimits())
        assertThat(s.limitBreach("X", BigDecimal("999999"))).isNull()
    }

    @Test
    fun `zero capital disables enforcement`() {
        val s = state("100", mapOf("X" to "100"), BookLimits(maxGrossExposure = BigDecimal("3")), capital = "0")
        assertThat(s.limitBreach("X", BigDecimal("100"))).isNull()
    }
}
