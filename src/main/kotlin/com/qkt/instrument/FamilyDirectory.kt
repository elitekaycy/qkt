package com.qkt.instrument

import java.math.BigDecimal

/** One catalogued contract of an event family: its qkt [symbol] and its [terms]. */
data class FamilyContract(
    val symbol: String,
    val terms: EventTerms,
)

/** One event of a family open at some instant: its [event] id, when it stops trading, its contracts in strike order. */
data class FamilyEvent(
    val event: String,
    val closeAtMs: Long,
    val contracts: List<FamilyContract>,
)

/**
 * The declared event families as slots and event `CHAIN:` streams read them, from the event catalog behind
 * [instruments] ([EventDirectory]) and each contract's [EventTerms]: a family's contracts, and an [OpenEvents]
 * cursor that answers which of its events are open as time moves forward.
 */
class FamilyDirectory(
    private val instruments: InstrumentRegistry,
) {
    /** True when [root] (`KALSHI:KXINXU`) is a declared event family. */
    fun isFamily(root: String): Boolean = instruments.events()?.family(root) != null

    /** Every catalogued contract of family [root] in expiry order; empty for an undeclared family. */
    fun contracts(root: String): List<FamilyContract> =
        instruments.events()?.contracts(root).orEmpty().map { symbol ->
            FamilyContract(symbol, requireNotNull(instruments.lookup(symbol)?.derivative as? EventTerms))
        }

    /** A cursor over family [root]'s open events, for a reader whose clock only moves forward. */
    fun openEvents(root: String): OpenEvents = OpenEvents(contracts(root))
}

/**
 * Which events of one family are open at an instant: a contract joins its event at `openAtMs` and leaves it at
 * `closeAtMs`, when its trading stops. Events come earliest close first (ties by event id), each with its contracts in
 * strike order: by floor (a cap when the contract has no floor), then cap, then symbol, so a ladder reads lowest
 * strike first and a contract with no bounds sorts by its code. Reads must come in time order; each costs one look at
 * the next join and leave, and the event list is rebuilt only when membership changes.
 */
class OpenEvents(
    contracts: List<FamilyContract>,
) {
    private val joining = ArrayDeque(contracts.sortedWith(compareBy({ it.terms.openAtMs }, { it.symbol })))
    private val open = ArrayList<FamilyContract>()
    private var events: List<FamilyEvent> = emptyList()

    /** The events open at [atMs], earliest close first; [atMs] must not go back in time between calls. */
    fun at(atMs: Long): List<FamilyEvent> {
        var changed = open.removeAll { it.terms.closeAtMs <= atMs }
        while (joining.isNotEmpty() && joining.first().terms.openAtMs <= atMs) {
            val next = joining.removeFirst()
            if (atMs < next.terms.closeAtMs) open.add(next)
            changed = true
        }
        if (changed) events = group(open)
        return events
    }

    private fun group(contracts: List<FamilyContract>): List<FamilyEvent> =
        contracts
            .groupBy { it.terms.event }
            .map { (event, members) ->
                FamilyEvent(event, members.minOf { it.terms.closeAtMs }, members.sortedWith(STRIKE_ORDER))
            }.sortedWith(compareBy({ it.closeAtMs }, { it.event }))

    private companion object {
        val BOUNDS: Comparator<BigDecimal?> = nullsLast(naturalOrder())

        /** By the first bound (strikeless contracts last), then the cap, then the symbol, so the order never ties. */
        val STRIKE_ORDER: Comparator<FamilyContract> =
            Comparator { a, b ->
                val first = BOUNDS.compare(a.firstBound(), b.firstBound())
                val cap = BOUNDS.compare(a.terms.predicate.cap, b.terms.predicate.cap)
                when {
                    first != 0 -> first
                    cap != 0 -> cap
                    else -> a.symbol.compareTo(b.symbol)
                }
            }

        fun FamilyContract.firstBound(): BigDecimal? = terms.predicate.floor ?: terms.predicate.cap
    }
}
