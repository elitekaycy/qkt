package com.qkt.backtest.report

import com.qkt.backtest.MetricsWindow
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.WindowReport
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A window is stated the same way in every output (#1451). */
class WindowFactsTest {
    private fun ms(day: String) =
        LocalDate
            .parse(day)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    private val report =
        PerformanceReport(
            realizedTotal = BigDecimal("-689.06"),
            unrealizedTotal = BigDecimal.ZERO,
            totalPnL = BigDecimal("-689.06"),
            tradeCount = 130,
            winRate = BigDecimal("0.32307692"),
            maxDrawdown = BigDecimal("0.149"),
            profitFactor = null,
            avgWin = BigDecimal.ZERO,
            avgLoss = BigDecimal.ZERO,
            largestWin = BigDecimal.ZERO,
            largestLoss = BigDecimal.ZERO,
            maxConsecutiveLosses = 0,
            sharpeRatio = null,
            calmarRatio = null,
            equityCurve = emptyList(),
            commissionPaid = BigDecimal("72.03"),
            swapPaid = BigDecimal("110.66"),
            roundTrips = 65,
            roundTripsWon = 21,
        )
    private val oos =
        WindowReport(
            window = MetricsWindow("out_of_sample", ms("2024-01-01"), ms("2026-01-31")),
            samples = 10,
            closingFills = 65,
            equityStart = BigDecimal("8006.12"),
            equityEnd = BigDecimal("7317.06"),
            report = report,
        )

    @Test
    fun `the split windows read as words with their inclusive days`() {
        assertThat(WindowFacts.label(oos)).isEqualTo("Out of sample")
        assertThat(WindowFacts.dates(oos)).isEqualTo("2024-01-01 to 2026-01-30")
        assertThat(WindowFacts.returnPct(oos)).isEqualTo("   (-8.6%)")
    }

    @Test
    fun `round trips and costs come from the window's own report`() {
        assertThat(WindowFacts.roundTrips(report)).isEqualTo("65 round trips, 32.3% won")
        assertThat(WindowFacts.costs(report, "USD")).isEqualTo("commission 72.03 USD, swap 110.66 USD")
        assertThat(WindowFacts.costs(report.copy(commissionPaid = BigDecimal.ZERO, swapPaid = BigDecimal.ZERO), null))
            .isEqualTo("none")
    }
}
