package com.qkt.marketdata.store.dukascopy

/**
 * A dukascopy instrument: its feed name and the divisor that turns the feed's integer prices into
 * real prices. Dukascopy sends prices as scaled integers — e.g. XAUUSD as thousandths, so a raw
 * `2345670` is `2345.670` (divide by 1000); EURUSD as 1e-5, so `109876` is `1.09876`.
 */
data class DukascopyInstrument(
    val dukascopyName: String,
    val priceDivisor: Long,
) {
    companion object {
        // Divisor = 10^decimals. Metals 3 dp; most FX 5 dp; JPY pairs 3 dp.
        private val TABLE: Map<String, DukascopyInstrument> =
            buildMap {
                fun put(
                    sym: String,
                    divisor: Long,
                ) = put(sym, DukascopyInstrument(sym, divisor))
                put("XAUUSD", 1000L)
                put("XAGUSD", 1000L)
                listOf("EURUSD", "GBPUSD", "AUDUSD", "NZDUSD", "USDCHF", "USDCAD").forEach { put(it, 100000L) }
                listOf("USDJPY", "EURJPY", "GBPJPY").forEach { put(it, 1000L) }
            }

        /** The instrument for a bare qkt symbol (no `NAME:` prefix), or null if dukascopy has no mapping. */
        fun ofOrNull(bareSymbol: String): DukascopyInstrument? = TABLE[bareSymbol]

        /** The instrument for a bare qkt symbol (no `NAME:` prefix), or fail if unmapped. */
        fun of(bareSymbol: String): DukascopyInstrument =
            ofOrNull(bareSymbol)
                ?: error("no dukascopy mapping for $bareSymbol; add it to DukascopyInstrument or pass --no-fetch")
    }
}
