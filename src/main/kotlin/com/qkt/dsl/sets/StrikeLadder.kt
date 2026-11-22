package com.qkt.dsl.sets

import com.qkt.instrument.EventTerms
import com.qkt.instrument.InstrumentMeta
import java.math.BigDecimal

/**
 * The strike ladders of a moving set's event contracts (runtime membership plan §4.5), from the catalog [rows] the
 * set ranks. An event's ladder is its contracts listed at the read (opened and not expired), ordered by
 * `floor_strike` (none lowest), then `cap_strike` (none highest): "below 73" sits under "73 to 74", which sits under
 * "74 or above". A contract with neither strike (a custom question) is on no ladder.
 *
 * A contract's neighbour one strike up is the listed contract of the same event with the nearest strictly higher
 * strikes, and one strike down the nearest strictly lower; contracts with the same strikes as it are skipped, and
 * several sharing the neighbour's strikes resolve to the first by symbol. None at the end of the ladder.
 */
internal class StrikeLadder(
    rows: List<InstrumentMeta>,
) {
    private val contracts: Map<String, InstrumentMeta> =
        rows.filter { it.derivative is EventTerms }.associateBy { it.qktSymbol.substringAfter(':') }

    /** The bare symbol of [symbol]'s neighbour [step] strikes away (+1 up, -1 down) at [atMs], or null when none. */
    fun neighbour(
        symbol: String,
        step: Int,
        atMs: Long,
    ): String? {
        val own = contracts[symbol]?.let(::rung) ?: return null
        val rungs =
            contracts.values
                .filter { DynamicSetMembership.alive(it, atMs) }
                .mapNotNull(::rung)
                .filter { it.terms.root == own.terms.root && it.terms.event == own.terms.event }
                .filter { compare(it, own) * step > 0 }
        val nearest = if (step > 0) rungs.minWithOrNull(::compare) else rungs.maxWithOrNull(::compare)
        return nearest?.let { n -> rungs.filter { compare(it, n) == 0 }.minOf { it.symbol } }
    }

    private class Rung(
        val symbol: String,
        val terms: EventTerms,
    ) {
        val floor: BigDecimal? get() = terms.predicate.floor
        val cap: BigDecimal? get() = terms.predicate.cap
    }

    private fun rung(meta: InstrumentMeta): Rung? {
        val terms = meta.derivative as EventTerms
        if (terms.predicate.floor == null && terms.predicate.cap == null) return null
        return Rung(meta.qktSymbol.substringAfter(':'), terms)
    }

    private fun compare(
        a: Rung,
        b: Rung,
    ): Int = compareValuesBy(a, b, { it.floor }, { Capped(it.cap) })

    /** A cap ordered with "none" highest. */
    private class Capped(
        val cap: BigDecimal?,
    ) : Comparable<Capped> {
        override fun compareTo(other: Capped): Int =
            when {
                cap == null && other.cap == null -> 0
                cap == null -> 1
                other.cap == null -> -1
                else -> cap.compareTo(other.cap)
            }
    }
}
