package com.qkt.backtest.report

import com.qkt.backtest.TradeRecord
import com.qkt.common.Side
import com.qkt.execution.Trade
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionTracker.LegAction
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradesCsvTest {
    private val trade = Trade("o,1", "XAUUSD", BigDecimal("2000"), BigDecimal("2"), Side.SELL, 60_000L)

    private fun position(qty: String) = Position("XAUUSD", BigDecimal(qty), BigDecimal("1990"))

    @Test
    fun `a fully audited fill writes every column, quoting fields that need it`() {
        val full =
            TradeRecord(
                trade = trade,
                realized = BigDecimal("20"),
                strategyId = "s1",
                orderType = "LIMIT",
                riskUsd = BigDecimal("5"),
                stopLossPrice = BigDecimal("2010"),
                takeProfitPrice = BigDecimal("1980"),
                nativeRealized = BigDecimal("18"),
                nativeCurrency = "EUR",
                accountRealized = BigDecimal("20"),
                accountCurrency = "USD",
                fxRate = BigDecimal("1.11"),
                fxRateTimestamp = 59_000L,
                fxSource = "fx \"feed\"",
                accountPositionBefore = position("2"),
                accountPositionAfter = position("0"),
                strategyPositionBefore = position("2"),
                strategyPositionAfter = position("0"),
                contractSize = BigDecimal("100"),
                legId = "leg-1",
                legAction = LegAction.CLOSED,
            )

        val row = TradesCsv.render(listOf(full)).lines()[1]

        assertThat(row).startsWith("60000,s1,XAUUSD,SELL,CLOSE_LONG,LIMIT,2,2000,20,20,20,18,EUR,20,USD,1.11,59000,")
        assertThat(row).contains(",\"fx \"\"feed\"\"\",5,\"o,1\",2010,1980,2,1990,0,1990,2,1990,0,1990,100,400000,")
        assertThat(row).endsWith(",true,leg-1,CLOSED")
    }

    @Test
    fun `a bare fill leaves the optional columns empty`() {
        val bare = TradeRecord(trade = trade, realized = BigDecimal.ZERO, strategyId = "s1", reducedExposure = false)

        val row = TradesCsv.render(listOf(bare)).lines()[1]

        assertThat(row)
            .isEqualTo("60000,s1,XAUUSD,SELL,UNKNOWN,,2,2000,0,0,,,,,,,,,,\"o,1\",,,,,,,,,,,,4000,false,,")
    }
}
