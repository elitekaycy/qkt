package com.qkt.marketdata.ingest.plan

import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.QktSymbols
import java.math.BigDecimal

/**
 * The instrument an import writes: its qkt symbol (`EXNESS:XAUUSD`), the bare name the tick store keys
 * on, and what the instrument registry knows of its price precision. [fxOrMetal] instruments have a
 * weekly open at the New York rollover that proves (or disproves) a source's clock.
 */
data class ImportTarget(
    val qktSymbol: String,
    val digits: Int?,
) {
    /** The name without its source prefix: the tick store directory. */
    val bare: String get() = qktSymbol.substringAfter(':')

    /** The source prefix (`EXNESS` in `EXNESS:XAUUSD`), or null for a bare symbol. */
    val source: String? get() = qktSymbol.substringBefore(':', "").ifEmpty { null }

    /** One price step, 10^-digits, when the digits are known. */
    val point: BigDecimal? get() = digits?.let { BigDecimal.ONE.movePointLeft(it) }

    /** True for FX pairs and spot metals, whose week opens at 17:00 (metals 18:00) New York time. */
    val fxOrMetal: Boolean
        get() {
            val name = bare.uppercase()
            if (name.length < PAIR_LENGTH) return false
            val base = name.substring(0, 3)
            val quote = name.substring(3, PAIR_LENGTH)
            return (base in CURRENCIES || base in METALS) && quote in CURRENCIES
        }

    /** When this market's week opens in New York time, the hour its weekly opens must show. */
    val market: com.qkt.marketdata.ingest.clock.MarketOpen
        get() =
            when {
                !fxOrMetal -> com.qkt.marketdata.ingest.clock.MarketOpen.ANY
                bare.uppercase().take(3) in METALS -> com.qkt.marketdata.ingest.clock.MarketOpen.METAL
                else -> com.qkt.marketdata.ingest.clock.MarketOpen.FX
            }

    companion object {
        private const val PAIR_LENGTH = 6
        private val CURRENCIES =
            setOf(
                "USD",
                "EUR",
                "GBP",
                "JPY",
                "CHF",
                "CAD",
                "AUD",
                "NZD",
                "SEK",
                "NOK",
                "DKK",
                "SGD",
                "HKD",
                "MXN",
                "ZAR",
                "TRY",
                "PLN",
                "CZK",
                "HUF",
                "CNH",
                "ILS",
                "THB",
            )
        private val METALS = setOf("XAU", "XAG", "XPT", "XPD")

        /** The target for [qktSymbol], its digits from [registry] unless [digits] overrides them. */
        fun of(
            qktSymbol: String,
            registry: InstrumentRegistry,
            digits: Int?,
        ): ImportTarget {
            require(qktSymbol.isNotBlank()) { "--as needs a symbol, e.g. --as EXNESS:XAUUSD" }
            if (':' in qktSymbol) QktSymbols.requireFileSafe(qktSymbol)
            require(qktSymbol.substringAfter(':').matches(Regex("[A-Za-z0-9_.@-]+"))) {
                "'$qktSymbol' cannot be a file name in the store; use letters, digits and _ . @ -"
            }
            return ImportTarget(qktSymbol, digits ?: registry.lookup(qktSymbol)?.digits)
        }
    }
}
