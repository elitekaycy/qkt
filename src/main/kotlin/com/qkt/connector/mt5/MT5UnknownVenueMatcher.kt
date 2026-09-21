package com.qkt.connector.mt5

/** A resting order or an open position at the venue that may be the order whose outcome is unknown. */
internal sealed interface UnknownVenueMatch {
    data class Pending(
        val order: MT5PendingOrder,
    ) : UnknownVenueMatch

    data class Position(
        val position: MT5Position,
    ) : UnknownVenueMatch
}

/**
 * Finds, among the venue's resting orders and open positions, the one an unanswered placement
 * became. Tickets the broker already owns are never candidates. E.g. placement id
 * `mt5-77-gold-1700000000-12` for 0.10 lots: a position carrying that id and that volume is the
 * match; with no id match at all, the older comment-and-timing correlation is tried instead.
 */
internal class MT5UnknownVenueMatcher(
    private val books: MT5BrokerState,
) {
    /** Everything plausible ([pendingCandidates], [positionCandidates]) and what actually correlates. */
    data class Result(
        val pendingCandidates: List<MT5PendingOrder>,
        val positionCandidates: List<MT5Position>,
        val matches: List<UnknownVenueMatch>,
    )

    fun match(
        pendings: List<MT5PendingOrder>,
        positions: List<MT5Position>,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        brokerSymbol: String,
        wireComment: String,
    ): Result {
        val pendingCandidates =
            pendings.filter {
                it.ticket > 0L &&
                    !books.pendingBook.isPending(it.ticket) &&
                    it.symbol == brokerSymbol &&
                    matchesOrderComment(it.comment, wireComment)
            }
        val positionCandidates =
            positions.filter {
                !books.positionBook.isAttributed(it.ticket) &&
                    it.symbol == brokerSymbol &&
                    matchesOrderComment(it.comment, wireComment)
            }
        // An id match still has to be this order's size: colliding comments from another
        // strategy under the same magic can carry the same id with a different volume (#1155).
        val exactMatches: List<UnknownVenueMatch> =
            pendingCandidates
                .filter {
                    it.clientOrderId == placement.clientOrderId &&
                        it.volume.compareTo(placement.volume) == 0
                }.map { UnknownVenueMatch.Pending(it) } +
                positionCandidates
                    .filter {
                        it.clientOrderId == placement.clientOrderId &&
                            it.volume.compareTo(placement.volume) == 0
                    }.map { UnknownVenueMatch.Position(it) }
        val fallbackMatches: List<UnknownVenueMatch> =
            if (exactMatches.isEmpty()) {
                pendingCandidates
                    .filter { MT5UnknownOutcomeMatching.matchesUnknownPending(it, placement, placementStartedAtMs) }
                    .map { UnknownVenueMatch.Pending(it) } +
                    positionCandidates
                        .filter {
                            MT5UnknownOutcomeMatching.matchesUnknownPosition(
                                it,
                                placement,
                                placementStartedAtMs,
                            )
                        }.map { UnknownVenueMatch.Position(it) }
            } else {
                emptyList()
            }
        val matches = if (exactMatches.isNotEmpty()) exactMatches else fallbackMatches
        return Result(pendingCandidates, positionCandidates, matches)
    }
}
