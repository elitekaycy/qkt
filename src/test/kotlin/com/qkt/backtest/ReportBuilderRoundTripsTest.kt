package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import com.qkt.positions.StrategyPositionTracker.LegAction
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Trade statistics count positions, not exit fills (#1452). */
class ReportBuilderRoundTripsTest {
    private fun fill(
        leg: String,
        realized: String,
        action: LegAction,
    ) = TradeRecord(
        trade = Trade("o-$leg", "XAUUSD", Money.of("2000"), Money.of("0.01"), Side.BUY, 0L),
        realized = BigDecimal(realized),
        strategyId = "s1",
        reducedExposure = action == LegAction.CLOSED,
        legId = leg,
        legAction = action,
    )

    @Test
    fun `half-filled exits make one round trip each and the win rate counts positions`() {
        val trades =
            listOf(
                fill("a", "-0.04", LegAction.OPENED),
                fill("a", "-0.04", LegAction.OPENED),
                fill("a", "10", LegAction.CLOSED),
                fill("a", "-0.5", LegAction.CLOSED),
                fill("b", "-0.02", LegAction.OPENED),
                fill("b", "-0.02", LegAction.OPENED),
                fill("b", "-3", LegAction.CLOSED),
                fill("b", "-3", LegAction.CLOSED),
            )

        val report =
            ReportBuilder.buildGlobal(
                trades = trades,
                equityCurve = emptyList(),
                finalRealized = BigDecimal("3.38"),
                finalUnrealized = BigDecimal.ZERO,
                annualizationFactor = BigDecimal("252"),
            )

        assertThat(report.tradeCount).isEqualTo(8)
        assertThat(report.roundTrips).isEqualTo(2)
        assertThat(report.roundTripsWon).isEqualTo(1)
        assertThat(report.winRate).isEqualByComparingTo("0.5")
        assertThat(report.largestWin).isEqualByComparingTo("9.42")
        assertThat(report.largestLoss).isEqualByComparingTo("-6.04")
    }
}
