package com.qkt.risk.book

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookRiskControllerWeightingTest {
    private val aPnl = listOf("0", "200", "-100", "300", "-50", "250")
    private val bPnl = listOf("0", "10", "21", "30", "41", "50")

    private fun run(allocation: Allocation): Map<String, BigDecimal> {
        val controller = BookRiskController(BookRiskConfig(allocation = allocation), BigDecimal("10000"))
        for (i in aPnl.indices) {
            controller.onSample(
                BookSnapshot(
                    timestampMs = (i + 1).toLong(),
                    bookEquity = BigDecimal("10000"),
                    exposure = Exposure(BigDecimal.ZERO, BigDecimal.ZERO, emptyMap()),
                    perStrategyPnl = mapOf("a" to BigDecimal(aPnl[i]), "b" to BigDecimal(bPnl[i])),
                ),
            )
        }
        return controller.state().allocationWeights
    }

    @Test
    fun `equal risk contribution gives the steadier strategy more weight`() {
        val w = run(Allocation(method = AllocationMethod.ERC, rebalanceEveryBars = 1))

        assertThat(w).containsOnlyKeys("a", "b")
        assertThat(w.getValue("b")).isGreaterThan(w.getValue("a"))
    }

    @Test
    fun `a vol target beyond reach scales the weights up to the leverage cap`() {
        val w =
            run(
                Allocation(
                    method = AllocationMethod.FIXED,
                    targetVol = BigDecimal("1000"),
                    rebalanceEveryBars = 1,
                    maxLeverage = BigDecimal("4"),
                ),
            )

        // FIXED is 1.0 each, gross 2: the cap allows a scale of 2.
        assertThat(w.getValue("a")).isEqualByComparingTo("2")
        assertThat(w.getValue("b")).isEqualByComparingTo("2")
    }

    @Test
    fun `a book with no positive peak reads no drawdown and keeps full size`() {
        val controller =
            BookRiskController(
                BookRiskConfig(deRisk = DeRisk(listOf(Rung(BigDecimal("0.01"), BigDecimal("0.5"))))),
                capital = BigDecimal.ZERO,
            )

        controller.onSample(
            BookSnapshot(1L, BigDecimal("-100"), Exposure(BigDecimal.ZERO, BigDecimal.ZERO, emptyMap()), emptyMap()),
        )

        assertThat(controller.state().deRiskFactor).isEqualByComparingTo("1")
    }
}
