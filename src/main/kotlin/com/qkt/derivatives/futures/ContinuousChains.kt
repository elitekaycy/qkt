package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.FuturesDirectory

/**
 * The continuous streams of a run, built on first use from the declared roots, catalogs and roll
 * histories in [directory]. A symbol that is not a continuous stream of a declared root has no chain.
 */
class ContinuousChains(
    private val directory: FuturesDirectory,
) {
    private val built = HashMap<String, ContinuousChain>()

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
        return ContinuousChain(root, catalog, directory.history(rootId), selector).also { built[symbol] = it }
    }
}
