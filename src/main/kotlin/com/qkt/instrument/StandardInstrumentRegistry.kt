package com.qkt.instrument

import java.math.BigDecimal

/**
 * Built-in [InstrumentMeta] for the FX majors and metals a backtest can auto-fetch from dukascopy.
 *
 * A backtest has no live broker to query `/symbol_info`, but the contract specs for these
 * instruments are industry-standard and stable — one lot of gold is 100 oz, one lot of a major
 * FX pair is 100,000 units, everywhere. So rather than force every user to hand-write the same
 * well-known numbers, this registry ships them. A backtest on `XAUUSD` then sizes and prices
 * correctly with no setup.
 *
 * It is the **fallback**, not the authority: an `instruments.yaml` (via [YamlInstrumentRegistry])
 * layered ahead of this one overrides any symbol — that is where you set a non-zero
 * [InstrumentMeta.commissionPerLot] (commission is broker-specific and defaults to zero here) or
 * add a symbol this table doesn't cover.
 *
 * The covered set matches `DukascopyInstrument` — everything qkt can fetch has standard specs here.
 * A symbol with no entry returns null, exactly like the live registry for an unknown symbol.
 */
object StandardInstrumentRegistry : InstrumentRegistry {
    /** contractSize and price-precision digits per bare symbol; the rest are uniform retail defaults. */
    private data class Spec(
        val contractSize: Long,
        val digits: Int,
    )

    private val SPECS: Map<String, Spec> =
        buildMap {
            put("XAUUSD", Spec(contractSize = 100, digits = 3))
            put("XAGUSD", Spec(contractSize = 5000, digits = 3))
            for (fx in listOf("EURUSD", "GBPUSD", "AUDUSD", "NZDUSD", "USDCHF", "USDCAD")) {
                put(fx, Spec(contractSize = 100_000, digits = 5))
            }
            for (jpy in listOf("USDJPY", "EURJPY", "GBPJPY")) {
                put(jpy, Spec(contractSize = 100_000, digits = 3))
            }
        }

    override fun lookup(qktSymbol: String): InstrumentMeta? {
        val bare = qktSymbol.substringAfter(':')
        val spec = SPECS[bare] ?: return null
        return InstrumentMeta(
            qktSymbol = qktSymbol,
            contractSize = BigDecimal(spec.contractSize),
            volumeStep = RETAIL_VOLUME_STEP,
            volumeMin = RETAIL_VOLUME_STEP,
            volumeMax = null,
            pointSize = BigDecimal.ONE.movePointLeft(spec.digits),
            digits = spec.digits,
            tradeStopsLevelPoints = 0,
            commissionPerLot = BigDecimal.ZERO,
        )
    }

    /** The smallest lot and lot increment at virtually every retail FX/metals broker. */
    private val RETAIL_VOLUME_STEP = BigDecimal("0.01")
}
