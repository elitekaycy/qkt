package com.qkt.risk.book

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookRiskControllerRegimeTest {
    private fun snap(
        ts: Long,
        aPnl: String,
        bPnl: String,
    ) = BookSnapshot(
        timestampMs = ts,
        bookEquity = BigDecimal("10000"),
        exposure = Exposure(BigDecimal.ZERO, BigDecimal.ZERO, emptyMap()),
        perStrategyPnl = mapOf("a" to BigDecimal(aPnl), "b" to BigDecimal(bPnl)),
    )

    @Test
    fun `regime weighted returns supplied fractions`() {
        val controller =
            BookRiskController(
                BookRiskConfig(allocation = Allocation(method = AllocationMethod.REGIME_WEIGHTED)),
                BigDecimal("10000"),
            )
        controller.setRegimeWeights(mapOf("a" to BigDecimal("0.5"), "b" to BigDecimal("0.3")))
        controller.onSample(snap(1L, "0", "0"))

        assertThat(controller.state().scaleFor("a")).isEqualByComparingTo("0.5")
        assertThat(controller.state().scaleFor("b")).isEqualByComparingTo("0.3")
    }

    @Test
    fun `regime weighted missing id gets zero`() {
        val controller =
            BookRiskController(
                BookRiskConfig(allocation = Allocation(method = AllocationMethod.REGIME_WEIGHTED)),
                BigDecimal("10000"),
            )
        controller.setRegimeWeights(mapOf("a" to BigDecimal("1.0")))
        controller.onSample(snap(1L, "0", "0"))

        assertThat(controller.state().scaleFor("b")).isEqualByComparingTo("0")
    }

    @Test
    fun `empty regime weights leave scale at one`() {
        val controller =
            BookRiskController(
                BookRiskConfig(allocation = Allocation(method = AllocationMethod.REGIME_WEIGHTED)),
                BigDecimal("10000"),
            )
        controller.onSample(snap(1L, "0", "0"))

        assertThat(controller.state().scaleFor("a")).isEqualByComparingTo("1")
    }

    @Test
    fun `regime weights updated on next sample`() {
        val controller =
            BookRiskController(
                BookRiskConfig(allocation = Allocation(method = AllocationMethod.REGIME_WEIGHTED)),
                BigDecimal("10000"),
            )
        controller.setRegimeWeights(mapOf("a" to BigDecimal("1.0")))
        controller.onSample(snap(1L, "0", "0"))
        assertThat(controller.state().scaleFor("a")).isEqualByComparingTo("1")

        controller.setRegimeWeights(mapOf("a" to BigDecimal("0.25")))
        controller.onSample(snap(2L, "0", "0"))
        assertThat(controller.state().scaleFor("a")).isEqualByComparingTo("0.25")
    }
}
