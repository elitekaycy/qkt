package com.qkt.instrument.universe

import com.qkt.instrument.universe.UniverseFixtures.NOW_MS
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UniverseResolverTest {
    private val usd = UniverseFilter.Compare(CatalogField.CURRENCY, CompareOp.EQ, CatalogValue.Str("USD"))

    private fun members(
        venue: String,
        filter: UniverseFilter,
        n: Int,
        by: CatalogField,
        descending: Boolean = true,
    ) = UniverseResolver.members(UniverseFixtures.all, venue, filter, n, by, descending, NOW_MS).map { it.qktSymbol }

    @Test
    fun `only the venue's instruments are candidates, matched ignoring case`() {
        assertThat(
            members("exness", usd, 10, CatalogField.SYMBOL),
        ).containsExactly("EXNESS:XAUUSD", "EXNESS:EURUSD", "EXNESS:AAPLm")
        assertThat(members("CME", usd, 10, CatalogField.SYMBOL)).containsExactly("CME:ESZ6")
        assertThat(members("DERIBIT", usd, 10, CatalogField.SYMBOL)).isEmpty()
    }

    @Test
    fun `members rank by the field, highest first by default, and TOP takes the first n`() {
        assertThat(
            members("EXNESS", usd, 2, CatalogField.CONTRACT_SIZE),
        ).containsExactly("EXNESS:EURUSD", "EXNESS:XAUUSD")
        assertThat(members("EXNESS", usd, 2, CatalogField.CONTRACT_SIZE, descending = false))
            .containsExactly("EXNESS:AAPLm", "EXNESS:XAUUSD")
    }

    @Test
    fun `ties break by symbol and instruments missing the field sort last`() {
        val tied =
            listOf(
                UniverseFixtures.meta("V:B", contractSize = "5"),
                UniverseFixtures.meta("V:A", contractSize = "5"),
                UniverseFixtures.meta("V:C", contractSize = "7"),
            )
        val any = UniverseFilter.Compare(CatalogField.KIND, CompareOp.EQ, CatalogValue.Str("spot"))
        val ranked = UniverseResolver.members(tied, "V", any, 3, CatalogField.CONTRACT_SIZE, true, NOW_MS)
        assertThat(ranked.map { it.qktSymbol }).containsExactly("V:C", "V:A", "V:B")
        val byCategory =
            UniverseResolver.members(
                UniverseFixtures.all,
                "EXNESS",
                usd,
                3,
                CatalogField.FAMILY,
                true,
                NOW_MS,
            )
        assertThat(byCategory.map { it.qktSymbol }).containsExactly("EXNESS:AAPLm", "EXNESS:EURUSD", "EXNESS:XAUUSD")
        val mixed =
            UniverseResolver.members(
                UniverseFixtures.all,
                "EXNESS",
                usd,
                3,
                CatalogField.TICK_SIZE,
                false,
                NOW_MS,
            )
        assertThat(mixed.map { it.qktSymbol }).containsExactly("EXNESS:AAPLm", "EXNESS:EURUSD", "EXNESS:XAUUSD")
        val oneExpiring = listOf(UniverseFixtures.esDec, UniverseFixtures.meta("CME:NQZ6", currency = "USD"))
        assertThat(
            UniverseResolver.members(oneExpiring, "CME", usd, 2, CatalogField.DTE, true, NOW_MS).map { it.qktSymbol },
        ).containsExactly("CME:ESZ6", "CME:NQZ6")
        assertThat(
            UniverseResolver.members(oneExpiring.reversed(), "CME", usd, 2, CatalogField.DTE, true, NOW_MS).map {
                it.qktSymbol
            },
        ).containsExactly("CME:ESZ6", "CME:NQZ6")
    }

    @Test
    fun `TOP must be positive`() {
        assertThatThrownBy { members("EXNESS", usd, 0, CatalogField.SYMBOL) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("TOP must be a positive count: 0")
    }

    @Test
    fun `the default member cap is five hundred`() {
        assertThat(UniverseResolver.DEFAULT_MAX_MEMBERS).isEqualTo(500)
        assertThat(BigDecimal(UniverseResolver.DEFAULT_MAX_MEMBERS)).isPositive()
    }
}
