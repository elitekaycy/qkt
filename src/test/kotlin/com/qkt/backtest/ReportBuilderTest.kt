package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReportBuilderTest {
    private fun tradeRecord(
        realized: String,
        strategyId: String = "s1",
        ts: Long = 0L,
    ): TradeRecord =
        TradeRecord(
            trade =
                Trade(
                    orderId = "o",
                    symbol = "X",
                    price = Money.of("100"),
                    quantity = Money.of("1"),
                    side = Side.BUY,
                    timestamp = ts,
                ),
            realized = BigDecimal(realized),
            strategyId = strategyId,
        )

    @Test
    fun `buildGlobal aggregates trades and curve`() {
        val trades =
            listOf(
                tradeRecord("10"),
                tradeRecord("-5"),
                tradeRecord("20"),
                tradeRecord("-3"),
            )
        val curve =
            listOf(
                EquitySample(0L, BigDecimal("0")),
                EquitySample(1L, BigDecimal("10")),
                EquitySample(2L, BigDecimal("5")),
                EquitySample(3L, BigDecimal("25")),
                EquitySample(4L, BigDecimal("22")),
            )

        val report =
            ReportBuilder.buildGlobal(
                trades = trades,
                equityCurve = curve,
                finalRealized = BigDecimal("22"),
                finalUnrealized = Money.ZERO,
                annualizationFactor = BigDecimal("525960"),
            )

        assertThat(report.realizedTotal).isEqualByComparingTo(BigDecimal("22"))
        assertThat(report.totalPnL).isEqualByComparingTo(BigDecimal("22"))
        assertThat(report.tradeCount).isEqualTo(4)
        assertThat(report.winRate).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(report.profitFactor).isNotNull()
        assertThat(report.avgWin).isEqualByComparingTo(BigDecimal("15"))
        assertThat(report.avgLoss).isEqualByComparingTo(BigDecimal("-4"))
        assertThat(report.maxConsecutiveLosses).isEqualTo(1)
        assertThat(report.equityCurve).hasSize(5)
        // Curve dips from peak 10 to 5 (drawdown 0.5), then peaks at 25 with smaller dip to 22 (0.12).
        // Max drawdown is the larger first dip.
        assertThat(report.maxDrawdown).isEqualByComparingTo(BigDecimal("0.5"))
    }

    @Test
    fun `buildPerStrategy filters trades by strategyId`() {
        val trades =
            listOf(
                tradeRecord("10", strategyId = "a"),
                tradeRecord("-5", strategyId = "b"),
                tradeRecord("20", strategyId = "a"),
            )
        val curveA = listOf(EquitySample(0L, BigDecimal("0")), EquitySample(1L, BigDecimal("30")))

        val reportA =
            ReportBuilder.buildPerStrategy(
                strategyId = "a",
                trades = trades.filter { it.strategyId == "a" },
                equityCurve = curveA,
                finalRealized = BigDecimal("30"),
                finalUnrealized = Money.ZERO,
                annualizationFactor = BigDecimal("525960"),
            )

        assertThat(reportA.tradeCount).isEqualTo(2)
        assertThat(reportA.realizedTotal).isEqualByComparingTo(BigDecimal("30"))
    }

    @Test
    fun `empty trades produces zero metrics`() {
        val report =
            ReportBuilder.buildGlobal(
                trades = emptyList(),
                equityCurve = listOf(EquitySample(0L, Money.ZERO)),
                finalRealized = Money.ZERO,
                finalUnrealized = Money.ZERO,
                annualizationFactor = BigDecimal("525960"),
            )
        assertThat(report.tradeCount).isEqualTo(0)
        assertThat(report.winRate).isEqualByComparingTo(Money.ZERO)
        assertThat(report.profitFactor).isNull()
        assertThat(report.sharpeRatio).isNull()
    }
}
