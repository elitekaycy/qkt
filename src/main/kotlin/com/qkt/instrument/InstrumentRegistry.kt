package com.qkt.instrument

/**
 * Lookup table for per-strategy [InstrumentMeta] resolved at strategy load.
 *
 * Live strategies wrap an `MT5Broker`'s `/symbol_info` cache via [com.qkt.connector.mt5.MT5InstrumentRegistry].
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
     * Why this registry should know [qktSymbol] yet has no entry for it (e.g. a futures contract of a
     * declared root that is missing from its catalog), or null when the symbol is simply not its own.
     */
    fun missingReason(qktSymbol: String): String? = null

    /** The run's declared futures, or null when this registry knows none. */
    fun futures(): FuturesDirectory? = null

    /**
     * Returns the meta for [qktSymbol] or throws with a helpful message.
     *
     * Callers in the strategy-load path use this so a missing instrument surfaces
     * immediately rather than degrading to a wrong-by-default value at fill time.
     */
    fun require(qktSymbol: String): InstrumentMeta =
        lookup(qktSymbol)
            ?: error(
                "no InstrumentMeta for $qktSymbol; risk and notional sizing need its contract size. " +
                    "For a backtest, add it to data/instruments.yaml (or pass --instruments <file>); " +
                    "generate the entry from your broker with " +
                    "qkt instruments pull --symbols <ACCOUNT>:${qktSymbol.substringAfter(':')} " +
                    "--as-prefix ${qktSymbol.substringBefore(':')} --out data/instruments.yaml. " +
                    "Live, the broker must expose it via /symbol_info",
            )
}

/**
 * No-op registry — every [lookup] returns `null`, every [require] throws the standard
 * "no InstrumentMeta" error. Used as the default in [com.qkt.strategy.StrategyContext]
 * for test contexts that don't exercise instrument-meta-aware paths (`SizeRiskAbs`,
 * `SizeRiskFrac`). Production paths construct a real registry explicitly.
 */
object NoopInstrumentRegistry : InstrumentRegistry {
    override fun lookup(qktSymbol: String): InstrumentMeta? = null
}
