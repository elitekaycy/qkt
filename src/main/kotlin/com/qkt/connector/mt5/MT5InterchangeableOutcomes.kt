package com.qkt.connector.mt5

import java.math.BigDecimal

/**
 * Pairs placements whose acknowledgements were lost with look-alike venue positions, when the
 * pairing is provable rather than a guess.
 *
 * The venue keeps only a prefix of each order comment, so a burst of identical orders — say
 * ten `STACK_AT` legs, `dsl-gold_scale_burst_fixed--8-stack-tier7-entry` … `tier9-entry`, all
 * 0.05 lots with the same stop and target — lands as positions that all read
 * `dsl-gold_scale_burst_fixe`. If three of those sends time out, each resolution sees three
 * candidates and cannot tell which is its own. The positions are interchangeable, though, and
 * when exactly as many unanswered orders of that shape are in flight as there are candidates,
 * every candidate is one of theirs. They are then paired in order: the Nth unanswered order by
 * send time takes the Nth candidate by venue open time.
 *
 * Anything short of that stays unresolved, as before: one unanswered order and two look-alikes
 * (one could be another strategy's), candidates that differ in size, side, stop, target or
 * stored comment, or any candidate opened before the first of these orders was sent.
 */
internal object MT5InterchangeableOutcomes {
    /** How far the venue clock may run behind the local send time and still count as after it. */
    const val VENUE_CLOCK_SKEW_MS: Long = 5_000L

    /** An unanswered placement: its engine order id, what was sent, when, and on which venue symbol. */
    data class InFlight(
        val orderId: String,
        val placement: MT5OrderRequest,
        val startedAtMs: Long,
        val brokerSymbol: String,
    )

    /** The candidate [self] owns among [matches], or null when the pairing is not provable. */
    fun pick(
        self: InFlight,
        matches: List<MT5Position>,
        inFlight: Collection<InFlight>,
    ): MT5Position? {
        if (matches.size < 2) return null
        val first = matches.first()
        if (matches.any { !interchangeable(first, it) }) return null
        val siblings =
            inFlight
                .filter { sameShape(it, self) }
                .sortedWith(compareBy<InFlight>({ it.startedAtMs }, { it.orderId }))
        if (siblings.size != matches.size) return null
        val earliestSendMs = siblings.first().startedAtMs
        if (matches.any { venueMs(it) < earliestSendMs - VENUE_CLOCK_SKEW_MS }) return null
        val rank = siblings.indexOfFirst { it.orderId == self.orderId }
        if (rank < 0) return null
        return matches.sortedWith(compareBy<MT5Position>({ venueMs(it) }, { it.ticket }))[rank]
    }

    private fun venueMs(position: MT5Position): Long = MT5UnknownOutcomeMatching.venueEpochMs(position.openTime)

    private fun interchangeable(
        a: MT5Position,
        b: MT5Position,
    ): Boolean =
        a.symbol == b.symbol &&
            a.type == b.type &&
            a.volume.compareTo(b.volume) == 0 &&
            a.sl.compareTo(b.sl) == 0 &&
            a.tp.compareTo(b.tp) == 0 &&
            a.comment == b.comment

    private fun sameShape(
        a: InFlight,
        b: InFlight,
    ): Boolean =
        a.brokerSymbol == b.brokerSymbol &&
            a.placement.type.equals(b.placement.type, ignoreCase = true) &&
            a.placement.volume.compareTo(b.placement.volume) == 0 &&
            sameLevel(a.placement.sl, b.placement.sl) &&
            sameLevel(a.placement.tp, b.placement.tp) &&
            a.placement.comment.take(MT5_COMMENT_LIMIT) == b.placement.comment.take(MT5_COMMENT_LIMIT)

    private fun sameLevel(
        a: BigDecimal?,
        b: BigDecimal?,
    ): Boolean = if (a == null || b == null) a == b else a.compareTo(b) == 0
}
