package com.qkt.broker.mt5

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5SymbolTest {
    @Test
    fun `exness suffix and alias applied on toBroker`() {
        val s = MT5Symbol(MT5DefaultProfiles.exness.symbolPolicy)
        assertThat(s.toBroker("EURUSD")).isEqualTo("EURUSDm")
        assertThat(s.toBroker("NAS100")).isEqualTo("USTECm")
        assertThat(s.toBroker("UKOIL")).isEqualTo("XBRUSDm")
    }

    @Test
    fun `exness toQkt strips suffix and reverses alias`() {
        val s = MT5Symbol(MT5DefaultProfiles.exness.symbolPolicy)
        assertThat(s.toQkt("EURUSDm")).isEqualTo("EURUSD")
        assertThat(s.toQkt("USTECm")).isEqualTo("NAS100")
        assertThat(s.toQkt("XBRUSDm")).isEqualTo("UKOIL")
    }

    @Test
    fun `round-trip yields original qkt symbol`() {
        val s = MT5Symbol(MT5DefaultProfiles.exness.symbolPolicy)
        for (q in listOf("EURUSD", "GBPUSD", "NAS100", "US500", "UKOIL")) {
            assertThat(s.toQkt(s.toBroker(q))).isEqualTo(q)
        }
    }

    @Test
    fun `empty suffix passes through`() {
        val s = MT5Symbol(SymbolPolicy(suffix = ""))
        assertThat(s.toBroker("EURUSD")).isEqualTo("EURUSD")
        assertThat(s.toQkt("EURUSD")).isEqualTo("EURUSD")
    }

    @Test
    fun `icmarkets dot-raw suffix`() {
        val s = MT5Symbol(MT5DefaultProfiles.icmarkets.symbolPolicy)
        assertThat(s.toBroker("EURUSD")).isEqualTo("EURUSD.raw")
        assertThat(s.toQkt("EURUSD.raw")).isEqualTo("EURUSD")
    }

    @Test
    fun `unknown alias passes through unchanged`() {
        val s = MT5Symbol(MT5DefaultProfiles.exness.symbolPolicy)
        assertThat(s.toBroker("XAUUSD")).isEqualTo("XAUUSDm")
        assertThat(s.toQkt("XAUUSDm")).isEqualTo("XAUUSD")
    }
}
