package com.qkt.instrument

/**
 * The declared futures of a run: each root's spec, contract catalog and measured roll history.
 * Handed out raw so the instrument package never depends on the continuous-stream logic that is
 * built from these parts.
 */
interface FuturesDirectory {
    /** The declared root [rootId] (`BINANCE_UM:BTCUSDT`), or null. */
    fun root(rootId: String): FuturesRoot?

    /** [rootId]'s contract catalog, or null when none has been fetched. */
    fun catalog(rootId: String): ContractCatalog?

    /** [rootId]'s measured roll history, or null when none has been built. */
    fun history(rootId: String): RollHistory?

    /** The root of a continuous symbol (`BINANCE_UM:BTCUSDT@front` → `BINANCE_UM:BTCUSDT`), or null. */
    fun rootOfContinuous(symbol: String): String?
}
