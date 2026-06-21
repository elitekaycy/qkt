package com.qkt.risk.book

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeRiskLadderTest {
    private fun ladder() =
        DeRiskLadder(
            listOf(
                Rung(BigDecimal("0.02"), BigDecimal("0.8")),
                Rung(BigDecimal("0.04"), BigDecimal("0.4")),
                Rung(BigDecimal("0.06"), BigDecimal("0.0"), cooldownBars = 2),
            ),
        )

    @Test
    fun `factor steps down as drawdown deepens`() {
        val l = ladder()
        assertThat(l.factorFor(BigDecimal("0.00"))).isEqualByComparingTo("1.0")
        assertThat(l.factorFor(BigDecimal("0.03"))).isEqualByComparingTo("0.8")
        assertThat(l.factorFor(BigDecimal("0.05"))).isEqualByComparingTo("0.4")
        assertThat(l.factorFor(BigDecimal("0.07"))).isEqualByComparingTo("0.0")
    }

    @Test
    fun `kill rung cooldown holds factor zero for N samples after recovery`() {
        val l = ladder()
        assertThat(l.factorFor(BigDecimal("0.07"))).isEqualByComparingTo("0.0") // kill, cooldown=2
        assertThat(l.factorFor(BigDecimal("0.03"))).isEqualByComparingTo("0.0") // recovered, hold (2->1)
        assertThat(l.factorFor(BigDecimal("0.03"))).isEqualByComparingTo("0.0") // hold (1->0)
        assertThat(l.factorFor(BigDecimal("0.03"))).isEqualByComparingTo("0.8") // cooldown elapsed
    }
}
