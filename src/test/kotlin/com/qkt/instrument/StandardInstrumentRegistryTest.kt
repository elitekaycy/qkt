package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StandardInstrumentRegistryTest {
    @Test
    fun `gold resolves standard contract specs from a broker-prefixed symbol`() {
        val meta = StandardInstrumentRegistry.lookup("BACKTEST:XAUUSD")!!
        assertThat(meta.qktSymbol).isEqualTo("BACKTEST:XAUUSD")
        assertThat(meta.contractSize).isEqualByComparingTo(BigDecimal("100"))
        assertThat(meta.digits).isEqualTo(3)
        assertThat(meta.pointSize).isEqualByComparingTo(BigDecimal("0.001"))
        assertThat(meta.commissionPerLot).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `a bare symbol with no prefix also resolves`() {
        assertThat(StandardInstrumentRegistry.lookup("XAUUSD")!!.contractSize)
            .isEqualByComparingTo(BigDecimal("100"))
    }

    @Test
    fun `fx majors and jpy pairs get the right contract size and precision`() {
        val eur = StandardInstrumentRegistry.lookup("BACKTEST:EURUSD")!!
        assertThat(eur.contractSize).isEqualByComparingTo(BigDecimal("100000"))
        assertThat(eur.digits).isEqualTo(5)
        assertThat(eur.pointSize).isEqualByComparingTo(BigDecimal("0.00001"))

        val jpy = StandardInstrumentRegistry.lookup("BACKTEST:USDJPY")!!
        assertThat(jpy.contractSize).isEqualByComparingTo(BigDecimal("100000"))
        assertThat(jpy.digits).isEqualTo(3)
    }

    @Test
    fun `an unknown symbol returns null like the live registry`() {
        assertThat(StandardInstrumentRegistry.lookup("BACKTEST:BTCUSDT")).isNull()
    }
}
