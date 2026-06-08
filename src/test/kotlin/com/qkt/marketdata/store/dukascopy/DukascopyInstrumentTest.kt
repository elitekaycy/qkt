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
    fun `unknown symbol fails with a clear message`() {
        assertThatThrownBy { DukascopyInstrument.of("WAT") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no dukascopy mapping for WAT")
    }
}
