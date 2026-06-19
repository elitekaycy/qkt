package com.qkt.backtest

import com.qkt.common.Side
import com.qkt.execution.Trade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ReportBuilderTurnoverTest {
    private fun tr(price: String, qty: String, realized: String) =
        TradeRecord(
            trade = Trade("o1", "BTCUSDT", BigDecimal(price), BigDecimal(qty), Side.BUY, 0L),
            realized = BigDecimal(realized),
            strategyId = "s",
        )

    @Test
    fun `turnover is traded notional over starting equity`() {
        val curve = listOf(EquitySample(0L, BigDecimal("10000")), EquitySample(1L, BigDecimal("10100")))
        // tradedNotional 20000 / startingEquity 10000 = 2.0
        val r =
            ReportBuilder.buildGlobal(
                trades = listOf(tr("100", "1", "100")),
                equityCurve = curve,
                finalRealized = BigDecimal("100"),
                finalUnrealized = BigDecimal.ZERO,
                annualizationFactor = BigDecimal("252"),
                tradedNotional = BigDecimal("20000"),
            )
        assertThat(r.turnover).isEqualByComparingTo("2.0")
    }

    @Test
    fun `turnover is zero without a capital basis`() {
        val curve = listOf(EquitySample(0L, BigDecimal.ZERO), EquitySample(1L, BigDecimal.ZERO))
        val r =
            ReportBuilder.buildGlobal(
                trades = listOf(tr("100", "1", "0")),
                equityCurve = curve,
                finalRealized = BigDecimal.ZERO,
                finalUnrealized = BigDecimal.ZERO,
                annualizationFactor = BigDecimal("252"),
                tradedNotional = BigDecimal("20000"),
            )
        assertThat(r.turnover).isEqualByComparingTo("0")
    }

    @Test
    fun `sortino is populated from the curve when no metrics supplied`() {
        val curve = listOf("100", "120", "90", "130").mapIndexed { i, v -> EquitySample(i.toLong(), BigDecimal(v)) }
        val r =
            ReportBuilder.buildGlobal(
                trades = emptyList(),
                equityCurve = curve,
                finalRealized = BigDecimal("30"),
                finalUnrealized = BigDecimal.ZERO,
                annualizationFactor = BigDecimal("252"),
            )
        assertThat(r.sortinoRatio).isNotNull()
    }
}
