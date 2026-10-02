package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.FuturesDirectory
import com.qkt.instrument.RollHistory
import java.util.concurrent.ConcurrentHashMap

/**
 * The continuous streams of a run, built on first use from the declared roots, catalogs and roll
 * histories in [directory]. A symbol that is not a continuous stream of a declared root has no chain.
 * A live session extends a root's history with each roll it measures ([useHistory]); its streams are
 * rebuilt on the longer history, which leaves every contract already mapped where it was. Safe to read
 * from the feed's thread and the engine's.
 */
class ContinuousChains(
    private val directory: FuturesDirectory,
) {
    private val built = ConcurrentHashMap<String, ContinuousChain>()
    private val histories = ConcurrentHashMap<String, RollHistory>()

    /** True when [symbol] names a continuous stream of a declared root; never builds the chain. */
    fun isContinuous(symbol: String): Boolean = directory.rootOfContinuous(symbol) != null

    /** The chain behind [symbol] (`VENUE:ROOT@front`), or null when [symbol] is not a continuous stream. */
    fun chainFor(symbol: String): ContinuousChain? {
        built[symbol]?.let { return it }
        val rootId = directory.rootOfContinuous(symbol) ?: return null
        val root = requireNotNull(directory.root(rootId)) { "futures root $rootId is not declared" }
        val catalog =
            requireNotNull(
                directory.catalog(rootId),
            ) { "no contract catalog for $rootId; run qkt fetch $rootId --catalog" }
        val selector =
            requireNotNull(ContinuousSelector.parse(symbol.substringAfter('@'))) { "unknown selector in $symbol" }
        val history = histories[rootId] ?: directory.history(rootId)
        return ContinuousChain(root, catalog, history, selector).also { built[symbol] = it }
    }

    /** Serves [rootId]'s streams from [history] from now on (the stored one extended by a roll measured live). */
    fun useHistory(
        rootId: String,
        history: RollHistory,
    ) {
        require(history.root == rootId) { "a history of ${history.root} cannot serve $rootId" }
        histories[rootId] = history
        built.keys.removeIf { directory.rootOfContinuous(it) == rootId }
    }
}
