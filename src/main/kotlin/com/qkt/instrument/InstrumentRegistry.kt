package com.qkt.instrument

/**
 * Lookup table for per-strategy [InstrumentMeta] resolved at strategy load.
 *
 * Live strategies wrap an `MT5Broker`'s `/symbol_info` cache via [com.qkt.instrument.MT5InstrumentRegistry].
 * Backtests load a static YAML file via [YamlInstrumentRegistry]. Both share this
 * interface so the trading pipeline doesn't fork by mode.
 *
 * Phase 30 chose a **hard error** on missing meta over a silent default — a strategy
 * declaring an undeclared symbol fails strategy load rather than silently sizing as
 * `contractSize=1`. The trade-off: every symbol needs a meta entry, but no `/100`-class
 * footgun survives.
 */
interface InstrumentRegistry {
    /** Returns the meta for [qktSymbol] or `null` when the registry has no entry. */
    fun lookup(qktSymbol: String): InstrumentMeta?

    /**
     * Returns the meta for [qktSymbol] or throws with a helpful message.
     *
     * Callers in the strategy-load path use this so a missing instrument surfaces
     * immediately rather than degrading to a wrong-by-default value at fill time.
     */
    fun require(qktSymbol: String): InstrumentMeta =
        lookup(qktSymbol)
            ?: error(
                "no InstrumentMeta for $qktSymbol; configure it in data/instruments.yaml " +
                    "for backtest or ensure the live broker exposes it via /symbol_info",
            )
}
