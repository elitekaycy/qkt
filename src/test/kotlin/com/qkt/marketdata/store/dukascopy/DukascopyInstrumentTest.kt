package com.qkt.marketdata.store.dukascopy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DukascopyInstrumentTest {
    @Test
    fun `resolves a known metal symbol`() {
        val i = DukascopyInstrument.of("XAUUSD")
        assertThat(i.dukascopyName).isEqualTo("XAUUSD")
        assertThat(i.priceDivisor).isEqualTo(1000L)
    }

    @Test
    fun `resolves a known fx symbol`() {
        assertThat(DukascopyInstrument.of("EURUSD").priceDivisor).isEqualTo(100000L)
    }

    @Test
    fun `resolves the dollar index to its datafeed token`() {
        val i = DukascopyInstrument.of("DXY")
        assertThat(i.dukascopyName).isEqualTo("DOLLARIDXUSD")
        assertThat(i.priceDivisor).isEqualTo(1000L)
    }

    @Test
    fun `resolves the us equity indices to their datafeed tokens`() {
        assertThat(DukascopyInstrument.of("SPX").dukascopyName).isEqualTo("USA500IDXUSD")
        assertThat(DukascopyInstrument.of("NDX").dukascopyName).isEqualTo("USATECHIDXUSD")
        assertThat(DukascopyInstrument.of("DJI").dukascopyName).isEqualTo("USA30IDXUSD")
        assertThat(DukascopyInstrument.of("RUT").dukascopyName).isEqualTo("USSC2000IDXUSD")
    }

    @Test
    fun `unknown symbol fails with a clear message`() {
        assertThatThrownBy { DukascopyInstrument.of("WAT") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no dukascopy mapping for WAT")
    }
}
