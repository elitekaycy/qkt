package com.qkt.instrument

/**
 * Registry that dispatches symbol lookups across multiple per-broker [InstrumentRegistry]
 * delegates. Each [MT5InstrumentRegistry] inside returns non-null only for symbols whose
 * prefix matches its own broker's profile name — so this registry just walks the
 * delegates in order and returns the first non-null result. Wrong-prefix lookups
 * naturally fall through.
 *
 * Replaces single-broker [MT5InstrumentRegistry] in [com.qkt.app.LiveSession.buildInstrumentRegistry]
 * so multi-MT5 deployments get correct contract-size + volume-step metadata for every
 * symbol regardless of which broker holds the position (#139). For the single-broker case,
 * `MultiMT5InstrumentRegistry(listOf(MT5InstrumentRegistry(b))).lookup(s)` produces the
 * same result as `MT5InstrumentRegistry(b).lookup(s)` — verified by parity test.
 */
class MultiMT5InstrumentRegistry(
    private val delegates: List<InstrumentRegistry>,
) : InstrumentRegistry {
    override fun lookup(qktSymbol: String): InstrumentMeta? {
        for (d in delegates) {
            d.lookup(qktSymbol)?.let { return it }
        }
        return null
    }
}
