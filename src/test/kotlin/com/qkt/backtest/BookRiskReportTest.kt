package com.qkt.backtest

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookRiskReportTest {
    private fun report(events: List<BookRiskEvent> = emptyList()) =
        BookRiskReport(
            series = listOf(BookRiskSample(0L, BigDecimal("50"), BigDecimal("10"), BigDecimal("1000"))),
            bookVol = null,
            maxGrossExposure = BigDecimal("50"),
            maxNetExposure = BigDecimal("10"),
            events = events,
        )

    @Test
    fun `a report starts with an empty event log`() {
        val r =
            BookRiskReport(
                series = emptyList(),
                bookVol = BigDecimal("0.1"),
                maxGrossExposure = BigDecimal.ZERO,
                maxNetExposure = BigDecimal.ZERO,
            )

        assertThat(r.events).isEmpty()
    }

    @Test
    fun `every book-risk event kind is ordered on the shared timestamp`() {
        val breach = BookRiskEvent.LimitBreach(30L, kind = "gross", detail = "gross 2.1 > 2.0")
        val derisk = BookRiskEvent.DeRiskChange(10L, factor = BigDecimal("0.5"))
        val rebalance = BookRiskEvent.Rebalance(20L, weights = mapOf("a" to BigDecimal("0.6")))

        val ordered = report(listOf(breach, derisk, rebalance)).events.sortedBy { it.timestampMs }

        assertThat(ordered).containsExactly(derisk, rebalance, breach)
        assertThat(breach.kind).isEqualTo("gross")
        assertThat(breach.detail).contains("2.1")
        assertThat(derisk.factor).isEqualByComparingTo("0.5")
        assertThat(rebalance.weights).containsEntry("a", BigDecimal("0.6"))
    }
}
