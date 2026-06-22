package com.qkt.risk.book

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookRiskControllerAllocationTest {
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
    fun `inverse-vol allocation tilts weight toward the steadier strategy`() {
        val controller =
            BookRiskController(
                config =
                    BookRiskConfig(
                        allocation = Allocation(method = AllocationMethod.INVERSE_VOL, rebalanceEveryBars = 1),
                    ),
                capital = BigDecimal("10000"),
            )
        // a swings hard (high vol); b drifts up smoothly (low vol).
        val aPnl = listOf("0", "200", "-100", "300", "-50", "250")
        val bPnl = listOf("0", "10", "21", "30", "41", "50")
        for (i in aPnl.indices) controller.onSample(snap((i + 1).toLong(), aPnl[i], bPnl[i]))

        val w = controller.state().allocationWeights
        assertThat(w).isNotEmpty
        assertThat(w.getValue("b")).isGreaterThan(w.getValue("a"))
    }

    @Test
    fun `no allocation config leaves weights empty and scale at one`() {
        val controller = BookRiskController(BookRiskConfig(), BigDecimal("10000"))
        controller.onSample(snap(1L, "0", "0"))
        controller.onSample(snap(2L, "100", "100"))
        assertThat(controller.state().allocationWeights).isEmpty()
        assertThat(controller.state().scaleFor("a")).isEqualByComparingTo("1")
    }
}
