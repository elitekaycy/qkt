package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.FutureTerms
import com.qkt.instrument.InstrumentRegistry
import java.util.concurrent.ConcurrentHashMap

/** The contract a futures stream follows at an instant: its [code] (no venue), [expiryMs], and when it next rolls. */
data class ActiveContract(
    val code: String,
    val expiryMs: Long,
    val nextRollMs: Long,
)

/**
 * Resolves which contract a futures stream follows at a given time, from [instruments]' futures
 * directory: a listed contract follows itself until its expiry (and "rolls" at expiry); a
 * continuous stream follows its chain — only where it can trade: from its first measured roll and
 * within the measured history — and next rolls at the schedule's next roll instant. Every other
 * symbol has none. Each stream's chain is built once.
 */
class ActiveContracts(
    val instruments: InstrumentRegistry,
) {
    private val chains = ConcurrentHashMap<String, Followed>()

    /** The contract [symbol] follows at [nowMs], or null (not futures, before or after its chain). */
    fun at(
        symbol: String,
        nowMs: Long,
    ): ActiveContract? {
        val terms = instruments.lookup(symbol)?.derivative as? FutureTerms ?: return null
        terms.expiryMs?.let { expiry -> return listed(symbol, expiry, nowMs) }
        val chain = chains.computeIfAbsent(symbol) { Followed(chainFor(it, terms.root)) }.chain ?: return null
        if (nowMs < chain.servedFromMs) return null
        val index = chain.indexAt(nowMs)?.takeIf(chain::covers) ?: return null
        val contract = chain.schedule.contracts[index]
        val nextRoll =
            chain.schedule.transitions
                .firstOrNull { it.atMs > nowMs }
                ?.atMs ?: contract.expiryMs
        return ActiveContract(contract.symbol, contract.expiryMs, minOf(nextRoll, contract.expiryMs))
    }

    private fun listed(
        symbol: String,
        expiryMs: Long,
        nowMs: Long,
    ): ActiveContract? = if (nowMs < expiryMs) ActiveContract(symbol.substringAfter(':'), expiryMs, expiryMs) else null

    /** [symbol]'s chain, or null when it cannot be built (the market source reports why at request time). */
    private fun chainFor(
        symbol: String,
        rootId: String,
    ): ContinuousChain? {
        val directory = instruments.futures() ?: return null
        val root = directory.root(rootId) ?: return null
        val catalog = directory.catalog(rootId) ?: return null
        val selector = ContinuousSelector.entries.firstOrNull { it.symbolFor(rootId) == symbol } ?: return null
        return try {
            ContinuousChain(root, catalog, directory.history(rootId), selector)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private class Followed(
        val chain: ContinuousChain?,
    )
}
