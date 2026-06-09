package com.qkt.instrument

/**
 * Resolves an [InstrumentMeta] by trying each layer in order and returning the first hit.
 *
 * Earlier layers override later ones. The backtest uses this to put a user's
 * [YamlInstrumentRegistry] ahead of the built-in [StandardInstrumentRegistry]: a symbol the YAML
 * defines wins; a symbol it omits falls through to the standard specs.
 *
 * e.g. layers `[yaml, standard]` with yaml defining only `BACKTEST:XAUUSD` (with commission) →
 * `XAUUSD` resolves from yaml, `EURUSD` falls through to the standard table.
 */
class LayeredInstrumentRegistry(
    private val layers: List<InstrumentRegistry>,
) : InstrumentRegistry {
    override fun lookup(qktSymbol: String): InstrumentMeta? {
        for (layer in layers) {
            layer.lookup(qktSymbol)?.let { return it }
        }
        return null
    }
}
