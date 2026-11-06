package com.qkt.pnl

import com.qkt.accounting.AccountingEngine
import com.qkt.common.CryptoCalendar
import com.qkt.instrument.CorporateAction
import com.qkt.instrument.CorporateActionSource
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DividendBookCurrencyTest {
    private val aapl =
        InstrumentMeta(
            qktSymbol = "EXNESS:AAPL",
            contractSize = BigDecimal(100),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = BigDecimal(10),
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
            currency = "USD",
            category = "Stocks",
        )

    @Test
    fun `a dividend in another currency than the share is refused at setup`() {
        val eurDividend = CorporateAction.Dividend(LocalDate.parse("2026-10-27"), BigDecimal("0.26"), "EUR")
        val registry =
            object : InstrumentRegistry {
                override fun lookup(qktSymbol: String) = aapl.takeIf { qktSymbol == it.qktSymbol }

                override fun corporateActions() = CorporateActionSource { listOf(eurDividend) }
            }

        assertThatThrownBy {
            DividendBook(
                registry,
                CryptoCalendar,
                StrategyPositionTracker(),
                AccountingEngine(),
                MarketPriceTracker(),
                listOf("div"),
                listOf("EXNESS:AAPL"),
            )
        }.hasMessage("EXNESS:AAPL dividend on 2026-10-27 is in EUR, the instrument in USD")
    }
}
