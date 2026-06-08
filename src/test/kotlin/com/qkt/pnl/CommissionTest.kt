package com.qkt.pnl

import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommissionTest {
    private fun registry(
        symbol: String,
        commissionPerLot: String,
    ): InstrumentRegistry {
        val meta =
            InstrumentMeta(
                qktSymbol = symbol,
                contractSize = BigDecimal("100"),
                volumeStep = BigDecimal("0.01"),
                volumeMin = BigDecimal("0.01"),
                volumeMax = null,
                pointSize = BigDecimal("0.001"),
                digits = 3,
                tradeStopsLevelPoints = 0,
                commissionPerLot = BigDecimal(commissionPerLot),
            )
        return object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = if (qktSymbol == symbol) meta else null
        }
    }

    @Test
    fun `per-lot commission is quantity times rate`() {
        val model = PerLotCommission(registry("XAU", "3.50"))
        assertThat(model.cost("XAU", BigDecimal("0.25"))).isEqualByComparingTo("0.875")
        assertThat(model.cost("XAU", BigDecimal("2.0"))).isEqualByComparingTo("7.00")
    }

    @Test
    fun `unknown symbol or zero rate costs nothing`() {
        assertThat(PerLotCommission(registry("XAU", "3.50")).cost("OTHER", BigDecimal("1.0")))
            .isEqualByComparingTo("0")
        assertThat(PerLotCommission(registry("XAU", "0")).cost("XAU", BigDecimal("1.0")))
            .isEqualByComparingTo("0")
        assertThat(PerLotCommission(NoopInstrumentRegistry).cost("XAU", BigDecimal("1.0")))
            .isEqualByComparingTo("0")
    }

    @Test
    fun `no-commission model is always zero`() {
        assertThat(NoCommission.cost("XAU", BigDecimal("5.0"))).isEqualByComparingTo("0")
    }

    @Test
    fun `book tallies total and per-strategy and returns the charged amount`() {
        val book = CommissionBook(PerLotCommission(registry("XAU", "3.50")))

        assertThat(book.charge("s1", "XAU", BigDecimal("0.25"))).isEqualByComparingTo("0.875")
        assertThat(book.charge("s1", "XAU", BigDecimal("0.25"))).isEqualByComparingTo("0.875")
        assertThat(book.charge("s2", "XAU", BigDecimal("1.0"))).isEqualByComparingTo("3.50")

        assertThat(book.total()).isEqualByComparingTo("5.25")
        assertThat(book.totalFor("s1")).isEqualByComparingTo("1.75")
        assertThat(book.totalFor("s2")).isEqualByComparingTo("3.50")
        assertThat(book.totalFor("unknown")).isEqualByComparingTo("0")
    }

    @Test
    fun `default book charges nothing`() {
        val book = CommissionBook()
        assertThat(book.charge("s1", "XAU", BigDecimal("10.0"))).isEqualByComparingTo("0")
        assertThat(book.total()).isEqualByComparingTo("0")
    }
}
