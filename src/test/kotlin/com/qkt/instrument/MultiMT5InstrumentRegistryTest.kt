package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #139 PR A — [MultiMT5InstrumentRegistry] dispatches symbol lookups across multiple
 * per-broker delegates and returns the first non-null hit. Each underlying
 * [MT5InstrumentRegistry] self-filters by broker prefix (an Exness-wrapping registry
 * returns null for `ICMARKETS:` symbols), so the chain pattern is naturally correct.
 */
class MultiMT5InstrumentRegistryTest {
    /** Test double that returns metadata for symbols starting with [matchPrefix] and null otherwise. */
    private class PrefixedRegistry(
        private val matchPrefix: String,
        private val contractSize: BigDecimal,
    ) : InstrumentRegistry {
        var lookupCount = 0
            private set

        override fun lookup(qktSymbol: String): InstrumentMeta? {
            lookupCount += 1
            if (!qktSymbol.startsWith(matchPrefix)) return null
            return InstrumentMeta(
                qktSymbol = qktSymbol,
                contractSize = contractSize,
                volumeStep = BigDecimal("0.01"),
                volumeMin = BigDecimal("0.01"),
                volumeMax = null,
                pointSize = BigDecimal("0.001"),
                digits = 3,
                tradeStopsLevelPoints = 0,
            )
        }
    }

    @Test
    fun `lookup dispatches to the first matching delegate by prefix`() {
        val exness = PrefixedRegistry("EXNESS:", BigDecimal("100"))
        val icmarkets = PrefixedRegistry("ICMARKETS:", BigDecimal("1"))

        val registry = MultiMT5InstrumentRegistry(listOf(exness, icmarkets))

        val exnessMeta = registry.lookup("EXNESS:XAUUSD")
        assertThat(exnessMeta).isNotNull()
        assertThat(exnessMeta!!.contractSize).isEqualByComparingTo("100")

        val icMeta = registry.lookup("ICMARKETS:SPX500")
        assertThat(icMeta).isNotNull()
        assertThat(icMeta!!.contractSize).isEqualByComparingTo("1")
    }

    @Test
    fun `lookup returns null when no delegate matches`() {
        val exness = PrefixedRegistry("EXNESS:", BigDecimal("100"))
        val registry = MultiMT5InstrumentRegistry(listOf(exness))

        assertThat(registry.lookup("UNKNOWN:XAUUSD")).isNull()
    }

    @Test
    fun `lookup short-circuits on first hit and does not query later delegates`() {
        val exness = PrefixedRegistry("EXNESS:", BigDecimal("100"))
        val icmarkets = PrefixedRegistry("ICMARKETS:", BigDecimal("1"))

        val registry = MultiMT5InstrumentRegistry(listOf(exness, icmarkets))
        registry.lookup("EXNESS:XAUUSD")

        assertThat(exness.lookupCount).isEqualTo(1)
        // Short-circuited — icmarkets must not be touched.
        assertThat(icmarkets.lookupCount).isEqualTo(0)
    }

    @Test
    fun `single-delegate registry passes lookups through verbatim (parity with MT5InstrumentRegistry usage)`() {
        val exness = PrefixedRegistry("EXNESS:", BigDecimal("100"))
        val multi = MultiMT5InstrumentRegistry(listOf(exness))

        // Match
        assertThat(multi.lookup("EXNESS:XAUUSD")?.contractSize).isEqualByComparingTo("100")
        // No match — same null result the legacy single-broker path returned
        assertThat(multi.lookup("OTHER:SYM")).isNull()
    }

    @Test
    fun `empty delegate list returns null for any lookup`() {
        val registry = MultiMT5InstrumentRegistry(emptyList())
        assertThat(registry.lookup("EXNESS:XAUUSD")).isNull()
    }
}
