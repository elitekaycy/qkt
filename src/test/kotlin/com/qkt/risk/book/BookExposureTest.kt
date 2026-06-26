package com.qkt.risk.book

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookExposureTest {
    private fun leg(
        s: String,
        sym: String,
        q: String,
        px: String,
        cs: String = "1",
    ) = Leg(s, sym, BigDecimal(q), BigDecimal(px), BigDecimal(cs))

    @Test
    fun `gross counts every leg, net cancels opposing same-symbol legs`() {
        val e = bookExposure(listOf(leg("a", "X", "1", "100"), leg("b", "X", "-1", "100")))
        assertThat(e.gross).isEqualByComparingTo("200")
        assertThat(e.net).isEqualByComparingTo("0")
        assertThat(e.perSymbolNet.getValue("X")).isEqualByComparingTo("0")
    }

    @Test
    fun `net sums absolute per-symbol nets across symbols`() {
        val e = bookExposure(listOf(leg("a", "X", "1", "100"), leg("a", "Y", "-2", "50")))
        assertThat(e.gross).isEqualByComparingTo("200")
        assertThat(e.net).isEqualByComparingTo("200")
    }

    @Test
    fun `contract size scales notional`() {
        val e = bookExposure(listOf(leg("a", "XAU", "1", "2000", "100")))
        assertThat(e.gross).isEqualByComparingTo("200000")
    }

    @Test
    fun `FX book exposure is aggregated in account currency`() {
        val e = bookExposure(listOf(leg("a", "BACKTEST:USDJPY", "100000", "150")))
        assertThat(e.gross).isEqualByComparingTo("100000")
        assertThat(e.net).isEqualByComparingTo("100000")
        assertThat(e.perSymbolNet.getValue("BACKTEST:USDJPY")).isEqualByComparingTo("100000")
    }
}
