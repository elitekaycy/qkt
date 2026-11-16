package com.qkt.marketdata.source

import com.qkt.instrument.EventTerms
import com.qkt.instrument.FamilyContract
import com.qkt.instrument.InstrumentRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Each event family's contracts as a live session's [instruments] list them now, with their terms. The family's list
 * is mapped again only when the directory hands a different one (a live family's listing changed), so a reader on
 * every tick costs one map read and gets the same list while nothing changed.
 */
internal class LiveFamilyContracts(
    private val instruments: InstrumentRegistry,
) {
    private class Mapped(
        val symbols: List<String>,
        val contracts: List<FamilyContract>,
    )

    private val cache = ConcurrentHashMap<String, Mapped>()

    /** Family [root]'s contracts in the directory's order; empty while it lists none. */
    fun of(root: String): List<FamilyContract> {
        val symbols = instruments.events()?.contracts(root).orEmpty()
        cache[root]?.takeIf { it.symbols === symbols }?.let { return it.contracts }
        val contracts = symbols.map { FamilyContract(it, instruments.lookup(it)?.derivative as EventTerms) }
        cache[root] = Mapped(symbols, contracts)
        return contracts
    }
}
