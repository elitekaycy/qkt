package com.qkt.instrument.universe

import com.qkt.instrument.universe.UniverseFixtures.NOW_MS
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CatalogFieldTest {
    private fun read(
        field: CatalogField,
        meta: com.qkt.instrument.InstrumentMeta,
    ) = field.read(meta, NOW_MS)

    @Test
    fun `kind tells spot, equity, future, perpetual, option and event apart`() {
        assertThat(CatalogField.kindOf(UniverseFixtures.eurusd)).isEqualTo("spot")
        assertThat(CatalogField.kindOf(UniverseFixtures.aapl)).isEqualTo("equity")
        assertThat(CatalogField.kindOf(UniverseFixtures.esDec)).isEqualTo("future")
        assertThat(CatalogField.kindOf(UniverseFixtures.btcPerp)).isEqualTo("perpetual")
        assertThat(CatalogField.kindOf(UniverseFixtures.btcCall)).isEqualTo("option")
        assertThat(CatalogField.kindOf(UniverseFixtures.event)).isEqualTo("event")
        assertThat(read(CatalogField.KIND, UniverseFixtures.event)).isEqualTo(CatalogValue.Str("event"))
    }

    @Test
    fun `plain fields read the instrument's own metadata`() {
        val gold = UniverseFixtures.xauusd
        assertThat(read(CatalogField.SYMBOL, gold)).isEqualTo(CatalogValue.Str("XAUUSD"))
        assertThat(read(CatalogField.CATEGORY, gold)).isEqualTo(CatalogValue.Str("Metals"))
        assertThat(read(CatalogField.CURRENCY, gold)).isEqualTo(CatalogValue.Str("USD"))
        assertThat(read(CatalogField.CONTRACT_SIZE, gold)).isEqualTo(CatalogValue.Num(BigDecimal(100)))
        assertThat(read(CatalogField.TICK_SIZE, gold)).isEqualTo(CatalogValue.Num(BigDecimal("0.05")))
        assertThat(
            read(CatalogField.TICK_SIZE, UniverseFixtures.eurusd),
        ).isEqualTo(CatalogValue.Num(BigDecimal("0.01")))
        assertThat(read(CatalogField.TRADE_MODE, UniverseFixtures.aapl)).isEqualTo(CatalogValue.Str("close_only"))
        assertThat(read(CatalogField.CATEGORY, UniverseFixtures.btcPerp)).isNull()
        assertThat(read(CatalogField.CURRENCY, UniverseFixtures.btcCall)).isNull()
    }

    @Test
    fun `derivative fields come from the terms and are absent on spot`() {
        assertThat(read(CatalogField.FAMILY, UniverseFixtures.esDec)).isEqualTo(CatalogValue.Str("ES"))
        assertThat(read(CatalogField.FAMILY, UniverseFixtures.event)).isEqualTo(CatalogValue.Str("KXINXU"))
        assertThat(read(CatalogField.FAMILY, UniverseFixtures.eurusd)).isNull()
        assertThat(read(CatalogField.UNDERLYING, UniverseFixtures.btcCall)).isEqualTo(CatalogValue.Str("btc_usdc"))
        assertThat(read(CatalogField.UNDERLYING, UniverseFixtures.esDec)).isNull()
        assertThat(read(CatalogField.EXPIRY_AT, UniverseFixtures.esDec))
            .isEqualTo(CatalogValue.Num(BigDecimal(NOW_MS + 30 * UniverseFixtures.DAY_MS)))
        assertThat(read(CatalogField.EXPIRY_AT, UniverseFixtures.btcCall))
            .isEqualTo(CatalogValue.Num(BigDecimal(NOW_MS + 90 * UniverseFixtures.DAY_MS)))
        assertThat(read(CatalogField.EXPIRY_AT, UniverseFixtures.event))
            .isEqualTo(CatalogValue.Num(BigDecimal(1_794_006_000_000L)))
        assertThat(read(CatalogField.EXPIRY_AT, UniverseFixtures.btcPerp)).isNull()
        assertThat(read(CatalogField.DTE, UniverseFixtures.esDec)).isEqualTo(CatalogValue.Num(BigDecimal("30.000000")))
        assertThat(read(CatalogField.DTE, UniverseFixtures.eurusd)).isNull()
    }

    @Test
    fun `fields parse by name ignoring case and list their spellings`() {
        assertThat(CatalogField.parse("Contract_Size")).isEqualTo(CatalogField.CONTRACT_SIZE)
        assertThat(CatalogField.parse("open_interest")).isNull()
        assertThat(CatalogField.spellings).startsWith("symbol, kind, category").contains("dte", "volume")
    }
}
