package com.qkt.broker

import com.qkt.common.Side
import com.qkt.instrument.InstrumentMeta
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SimulatedFillPriceTest {
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
    private val fillPrice = SimulatedFillPrice(syntheticSpreadPoints = 2)

    // Dukascopy-like quote: 0.780 wide around a 2000.000 mid.
    private val vendor =
        Tick("EXNESS:XAUUSD", BigDecimal("2000.000"), 0L, bid = BigDecimal("1999.610"), ask = BigDecimal("2000.390"))
    private val midOnly = Tick("EXNESS:XAUUSD", BigDecimal("2000.000"), 0L)

    @Test
    fun `without a stated spread a two-sided tick fills at its own quotes`() {
        assertThat(fillPrice.of(Side.BUY, vendor, null, xau)).isEqualByComparingTo("2000.390")
        assertThat(fillPrice.of(Side.SELL, vendor, null, xau)).isEqualByComparingTo("1999.610")
    }

    @Test
    fun `a fixed spread re-centres the vendor quote on its mid`() {
        val exness = xau.copy(spreadPoints = 260)

        assertThat(fillPrice.of(Side.BUY, vendor, null, exness)).isEqualByComparingTo("2000.130")
        assertThat(fillPrice.of(Side.SELL, vendor, null, exness)).isEqualByComparingTo("1999.870")
    }

    @Test
    fun `a minimum spread widens a thinner quote and keeps a wider one`() {
        val floor = xau.copy(minSpreadPoints = 1000)
        val thin = xau.copy(minSpreadPoints = 500)

        assertThat(fillPrice.of(Side.BUY, vendor, null, floor)).isEqualByComparingTo("2000.500")
        assertThat(fillPrice.of(Side.SELL, vendor, null, floor)).isEqualByComparingTo("1999.500")
        assertThat(fillPrice.of(Side.BUY, vendor, null, thin)).isEqualByComparingTo("2000.390")
    }

    @Test
    fun `a stated spread replaces the synthetic one on mid-only ticks`() {
        assertThat(fillPrice.of(Side.BUY, midOnly, null, xau)).isEqualByComparingTo("2000.001")
        assertThat(fillPrice.of(Side.BUY, midOnly, null, xau.copy(spreadPoints = 260))).isEqualByComparingTo("2000.130")
        assertThat(
            fillPrice.of(Side.SELL, midOnly, null, xau.copy(minSpreadPoints = 1)),
        ).isEqualByComparingTo("1999.999")
    }

    @Test
    fun `an instrument states one spread model, not both`() {
        assertThatThrownBy { xau.copy(spreadPoints = 260, minSpreadPoints = 100) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not both")
    }
}
