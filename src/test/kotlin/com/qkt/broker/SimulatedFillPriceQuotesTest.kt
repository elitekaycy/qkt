package com.qkt.broker

import com.qkt.common.Side
import com.qkt.instrument.InstrumentMeta
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The mt5-sim price lookups are tallied by the kind of quote they rest on (#1437). */
class SimulatedFillPriceQuotesTest {
    private val xau =
        InstrumentMeta(
            qktSymbol = "EXNESS:XAUUSD",
            contractSize = BigDecimal("100"),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = null,
            pointSize = BigDecimal("0.001"),
            digits = 3,
            tradeStopsLevelPoints = 0,
        )
    private val twoSided =
        Tick("EXNESS:XAUUSD", BigDecimal("2000.000"), 0L, bid = BigDecimal("1999.610"), ask = BigDecimal("2000.390"))
    private val midOnly = Tick("EXNESS:XAUUSD", BigDecimal("2000.000"), 0L)

    @Test
    fun `a two-sided tick at its own quotes counts as recorded`() {
        val price = SimulatedFillPrice(syntheticSpreadPoints = 2)
        price.of(Side.BUY, twoSided, null, xau)
        price.of(Side.SELL, twoSided, null, xau)

        assertThat(price.quotes()).isEqualTo(FillQuotes(recorded = 2))
    }

    @Test
    fun `a spread stated in instruments yaml counts as the venue spread`() {
        val price = SimulatedFillPrice(syntheticSpreadPoints = 2)
        price.of(Side.BUY, twoSided, null, xau.copy(spreadPoints = 260))
        price.of(Side.BUY, midOnly, null, xau.copy(minSpreadPoints = 500))

        assertThat(price.quotes()).isEqualTo(FillQuotes(venueSpread = 2))
    }

    @Test
    fun `a mid-only price with no stated spread counts as synthetic`() {
        val price = SimulatedFillPrice(syntheticSpreadPoints = 2)
        price.of(Side.BUY, midOnly, null, xau)
        price.of(Side.SELL, null, BigDecimal("2000"), xau.copy(minSpreadPoints = 1))

        assertThat(price.quotes()).isEqualTo(FillQuotes(synthetic = 2))
    }

    @Test
    fun `no price is no quote`() {
        val price = SimulatedFillPrice(syntheticSpreadPoints = 2)
        price.of(Side.BUY, null, null, xau)

        assertThat(price.quotes().any).isFalse()
    }
}
