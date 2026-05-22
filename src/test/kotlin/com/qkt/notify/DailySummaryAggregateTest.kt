package com.qkt.notify

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DailySummaryAggregateTest {
    private fun row(id: String) =
        StrategySummary(
            strategyId = id,
            equity = BigDecimal("1000"),
            equityDeltaPct = BigDecimal.ZERO,
            realizedToday = BigDecimal.ZERO,
            unrealized = BigDecimal.ZERO,
            tradesToday = 0,
            haltsToday = 0,
            positionsSummary = "flat",
        )

    @Test
    fun `aggregates rows from every session into one summary`() {
        val summary =
            aggregateDailySummary(
                rowsPerSession = listOf(listOf(row("alpha"), row("beta")), listOf(row("gamma"))),
                nowMs = 1_700_000_000_000L,
            )

        assertThat(summary.strategies.map { it.strategyId })
            .containsExactly("alpha", "beta", "gamma")
        assertThat(summary.timestamp).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `aggregates to an empty summary when there are no sessions`() {
        val summary = aggregateDailySummary(rowsPerSession = emptyList(), nowMs = 1_700_000_000_000L)

        assertThat(summary.strategies).isEmpty()
    }
}
