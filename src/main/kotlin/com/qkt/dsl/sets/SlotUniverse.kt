package com.qkt.dsl.sets

import com.qkt.instrument.EventTerms
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.SlotSelectors

/**
 * How a slot universe (`KALSHI:*@front.p70 WHERE ...`, runtime membership plan step 6) sees the venue's event
 * contracts: one row per family that has a contract listed and unexpired, its front contract (earliest expiry, then
 * symbol), so the filter and ranking read a family through the contract it trades next; the member is the family's
 * slot (`KXINXU@front.p70`), which the run follows like any declared slot.
 */
internal object SlotUniverse {
    /** The front contract of each family among [alive] (event contracts only), keyed by that contract's row. */
    fun fronts(alive: List<InstrumentMeta>): Map<InstrumentMeta, String> =
        alive
            .mapNotNull { meta -> (meta.derivative as? EventTerms)?.let { meta to it } }
            .groupBy { (_, terms) -> terms.root }
            .map { (root, contracts) ->
                contracts.minWith(compareBy({ it.second.expiryMs }, { it.first.qktSymbol })).first to root
            }.toMap()

    /** The slot of family [root] (`KALSHI:KXINXU`) for selector [slot] (`front.p70`), as a bare member symbol. */
    fun member(
        root: String,
        slot: String,
    ): String = "${root.substringAfter(':')}@$slot"

    /**
     * The contracts of an event ladder (`@front.*`, `@next.*`, `@each.*`) among [alive] at [atMs]: per family (only
     * [family] of [venue] when given), its events open then (listed, not yet closed), earliest close first and ties by
     * event id as slots order them, and of those the one [selector] names, or every one for `each`.
     */
    fun ladder(
        alive: List<InstrumentMeta>,
        venue: String,
        family: String?,
        selector: String,
        atMs: Long,
    ): List<InstrumentMeta> {
        val event = selector.substringBefore('.')
        return alive
            .mapNotNull { meta -> (meta.derivative as? EventTerms)?.let { meta to it } }
            .filter { (_, t) -> t.openAtMs <= atMs && atMs < t.closeAtMs }
            .filter { (_, t) -> family == null || t.root == "$venue:$family" }
            .groupBy { (_, t) -> t.root }
            .values
            .flatMap { contracts ->
                val events =
                    contracts
                        .groupBy { (_, t) -> t.event }
                        .entries
                        .sortedWith(compareBy({ e -> e.value.minOf { it.second.closeAtMs } }, { it.key }))
                val offset = SlotSelectors.eventOffset(event) ?: 0
                val chosen = if (event == EACH_EVENT) events else listOfNotNull(events.getOrNull(offset))
                chosen.flatMap { e -> e.value.map { it.first } }
            }
    }
}
