package com.qkt.derivatives.options

import com.qkt.instrument.OptionRight
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StructureRiskTest {
    private val near = 1_000L
    private val far = 2_000L

    private fun leg(
        right: OptionRight,
        strike: String,
        quantity: String,
        expiryMs: Long,
        price: String,
    ) = ExpiringLeg(
        OptionLeg(right, BigDecimal(strike), BigDecimal(quantity), BigDecimal.ONE),
        expiryMs,
        BigDecimal(price),
    )

    @Test
    fun `a put spread loses its width less its credit`() {
        val loss =
            StructureRisk.maxLoss(
                listOf(
                    leg(OptionRight.PUT, "81000", "-1", near, "646"),
                    leg(OptionRight.PUT, "78000", "1", near, "219"),
                ),
            )

        // value -646 + 219 = -427; worst payoff -3000 at or below 78000: 3000 - 427.
        assertThat(loss).isEqualByComparingTo("2573")
    }

    @Test
    fun `a calendar is judged at each expiry, never offset across them`() {
        val loss =
            StructureRisk.maxLoss(
                listOf(
                    leg(OptionRight.PUT, "80000", "1", near, "500"),
                    leg(OptionRight.PUT, "80000", "-1", far, "900"),
                ),
            )

        // The long put lapses worthless above 80000 (500 lost), then the short pays 80000 at 0 having taken 900.
        assertThat(loss).isEqualByComparingTo("79600")
    }

    @Test
    fun `an unbounded expiry makes the whole structure unbounded`() {
        val loss =
            StructureRisk.maxLoss(
                listOf(
                    leg(OptionRight.PUT, "80000", "1", near, "500"),
                    leg(OptionRight.CALL, "90000", "-1", far, "700"),
                ),
            )

        assertThat(loss).isNull()
    }
}
