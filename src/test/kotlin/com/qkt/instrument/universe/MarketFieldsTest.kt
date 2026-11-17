package com.qkt.instrument.universe

import com.qkt.instrument.universe.UniverseFixtures.NOW_MS
import com.qkt.instrument.universe.UniverseFixtures.eurusd
import com.qkt.instrument.universe.UniverseFixtures.xauusd
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketFieldsTest {
    /** EURUSD traded 50 and closed at 1.1 with a 0.0002 spread; XAUUSD traded 900 at 2400; nothing else is known. */
    private val facts =
        MarketFacts { field, symbol ->
            when (symbol to field) {
                "EXNESS:EURUSD" to CatalogField.VOLUME -> BigDecimal(50)
                "EXNESS:EURUSD" to CatalogField.CLOSE -> BigDecimal("1.1")
                "EXNESS:EURUSD" to CatalogField.SPREAD -> BigDecimal("0.0002")
                "EXNESS:XAUUSD" to CatalogField.VOLUME -> BigDecimal(900)
                "EXNESS:XAUUSD" to CatalogField.CLOSE -> BigDecimal(2400)
                else -> null
            }
        }

    private fun num(v: String) = CatalogValue.Num(BigDecimal(v))

    private fun volumeAbove(v: String) = UniverseFilter.Compare(CatalogField.VOLUME, CompareOp.GT, num(v))

    private val forex = UniverseFilter.Glob(CatalogField.CATEGORY, "Forex*")

    @Test
    fun `close, volume and spread are market fields read from the facts, null without them`() {
        assertThat(CatalogField.entries.filter { it.market }.map { it.token })
            .containsExactly("close", "volume", "spread")
        assertThat(CatalogField.parse("VOLUME")).isEqualTo(CatalogField.VOLUME)
        assertThat(CatalogField.CLOSE.read(eurusd, NOW_MS, facts)).isEqualTo(num("1.1"))
        assertThat(CatalogField.SPREAD.read(eurusd, NOW_MS, facts)).isEqualTo(num("0.0002"))
        assertThat(CatalogField.VOLUME.read(eurusd, NOW_MS)).isNull()
        assertThat(CatalogField.SYMBOL.read(eurusd, NOW_MS, facts)).isEqualTo(CatalogValue.Str("EURUSD"))
    }

    @Test
    fun `a market clause passes on its figure, and may pass whatever the figure is`() {
        val filter = volumeAbove("100")
        assertThat(filter.readsMarket).isTrue()
        assertThat(filter.matches(xauusd, NOW_MS, facts)).isTrue()
        assertThat(filter.matches(eurusd, NOW_MS, facts)).isFalse()
        assertThat(filter.matches(xauusd, NOW_MS)).isFalse()
        assertThat(filter.mayMatch(eurusd, NOW_MS)).isTrue()
        val oneOf = UniverseFilter.OneOf(CatalogField.CLOSE, listOf(num("2400"), num("1")))
        assertThat(oneOf.readsMarket).isTrue()
        assertThat(oneOf.matches(xauusd, NOW_MS, facts)).isTrue()
        assertThat(oneOf.mayMatch(eurusd, NOW_MS)).isTrue()
        val glob = UniverseFilter.Glob(CatalogField.CLOSE, "1.*")
        assertThat(glob.readsMarket).isTrue()
        assertThat(glob.matches(eurusd, NOW_MS, facts)).isTrue()
        assertThat(glob.mayMatch(xauusd, NOW_MS)).isTrue()
    }

    @Test
    fun `the catalog part of a filter decides what may pass, and negating a market clause may always pass`() {
        assertThat(forex.readsMarket).isFalse()
        assertThat(forex.mayMatch(xauusd, NOW_MS)).isFalse()
        val and = UniverseFilter.And(forex, volumeAbove("10"))
        assertThat(and.readsMarket).isTrue()
        assertThat(and.mayMatch(eurusd, NOW_MS)).isTrue()
        assertThat(and.mayMatch(xauusd, NOW_MS)).isFalse()
        assertThat(and.matches(eurusd, NOW_MS, facts)).isTrue()
        val or = UniverseFilter.Or(forex, volumeAbove("500"))
        assertThat(or.readsMarket).isTrue()
        assertThat(or.mayMatch(xauusd, NOW_MS)).isTrue()
        assertThat(or.matches(xauusd, NOW_MS, facts)).isTrue()
        assertThat(UniverseFilter.Or(forex, forex).mayMatch(xauusd, NOW_MS)).isFalse()
        val notMarket = UniverseFilter.Not(volumeAbove("10"))
        assertThat(notMarket.readsMarket).isTrue()
        assertThat(notMarket.mayMatch(xauusd, NOW_MS)).isTrue()
        assertThat(notMarket.matches(xauusd, NOW_MS, facts)).isFalse()
        val notForex = UniverseFilter.Not(forex)
        assertThat(notForex.readsMarket).isFalse()
        assertThat(notForex.mayMatch(xauusd, NOW_MS)).isTrue()
        assertThat(notForex.mayMatch(eurusd, NOW_MS)).isFalse()
    }

    @Test
    fun `a set ranks by a market figure, an instrument without one last`() {
        val catalog = listOf(eurusd, xauusd, UniverseFixtures.meta("EXNESS:GBPUSD", category = "Forex"))
        val ranked =
            UniverseResolver.members(
                catalog,
                "EXNESS",
                UniverseFilter.Not(UniverseFilter.Compare(CatalogField.SYMBOL, CompareOp.EQ, CatalogValue.Str("X"))),
                3,
                CatalogField.VOLUME,
                true,
                NOW_MS,
                facts,
            )
        assertThat(ranked.map { it.qktSymbol }).containsExactly("EXNESS:XAUUSD", "EXNESS:EURUSD", "EXNESS:GBPUSD")
    }
}
