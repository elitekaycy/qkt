package com.qkt.positions

import com.qkt.common.Side
import java.math.BigDecimal

/**
 * Applies venue position corrections to the leg ledger: a per-ticket venue position becomes
 * its own leg, a ticketless net figure replaces the owning strategy's book. Holds no state of
 * its own; [StrategyPositionTracker.reconcileNet] calls it, and it rewrites [legBooks] then
 * re-anchors [excursions], persists and reindexes [accountIndex] exactly as the tracker did.
 */
internal class VenuePositionReconciler(
    private val legBooks: StrategyLegBooks,
    private val primaryIds: PrimaryLegIds,
    private val excursions: PrimaryExcursions,
    private val accountIndex: AccountNetIndex,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(StrategyPositionTracker::class.java)

    /**
     * Apply a venue correction to a symbol's ledger. Only possible when exactly one strategy
     * trades the symbol — a figure cannot be attributed across several — otherwise the
     * correction is logged and left to the per-ticket reconcile. Returns true when the ledger
     * changed.
     *
     * With a [ticket] the correction describes one venue position (MT5 keys positions by
     * ticket, and a hedging account holds several per symbol): a leg already booked under that
     * ticket, or an unticketed book that already nets to it, is left alone; otherwise the
     * position is added as its own leg carrying the ticket so a later CLOSE targets it (#1103).
     * Without a ticket the figure is the symbol's net and replaces the book.
     */
    fun reconcileNet(
        symbol: String,
        signedQuantity: BigDecimal,
        avgEntryPrice: BigDecimal,
        openedAt: Long,
        source: String,
        ticket: String?,
        strategyId: String?,
    ): Boolean {
        val owner =
            strategyId ?: run {
                val owners = legBooks.holdersOf(symbol)
                if (owners.size > 1) {
                    log.warn(
                        "venue correction for {} from {} not applied: {} strategies hold it",
                        symbol,
                        source,
                        owners.size,
                    )
                    return false
                }
                owners.singleOrNull()?.key ?: run {
                    if (signedQuantity.signum() != 0) {
                        log.warn("venue correction for {} from {} not applied: no strategy holds it", symbol, source)
                    }
                    return false
                }
            }
        val books = legBooks.booksOrCreate(owner)
        if (ticket != null && signedQuantity.signum() != 0) {
            val book = books[symbol]
            if (book != null && !book.isEmpty()) {
                if (book.legByTicket(ticket) != null) {
                    log.debug("venue position {} on {} already booked for {}", ticket, symbol, owner)
                    return false
                }
                val unticketed = book.all().none { it.brokerTicket != null }
                if (unticketed && book.netQuantity().compareTo(signedQuantity) == 0) {
                    log.debug("book for {}/{} already nets to venue position {}", owner, symbol, ticket)
                    return false
                }
            }
            val target = book ?: LegBook(symbol).also { books[symbol] = it }
            val asPrimary = target.primary() == null
            target.add(
                PositionLeg(
                    legId = if (asPrimary) primaryIds.next(owner, symbol) else "$owner-$symbol-venue-$ticket",
                    symbol = symbol,
                    side = if (signedQuantity.signum() > 0) Side.BUY else Side.SELL,
                    quantity = signedQuantity.abs(),
                    entryPrice = avgEntryPrice,
                    openedAt = openedAt,
                    role = if (asPrimary) LegRole.PRIMARY else LegRole.INDEPENDENT,
                    brokerTicket = ticket,
                ),
            )
            log.info("venue position {} on {} booked for {} from {}", ticket, symbol, owner, source)
            excursions.sync(owner, symbol)
            legBooks.persist(owner, symbol)
            accountIndex.reindex(symbol)
            return true
        }
        if (signedQuantity.signum() == 0) {
            books.remove(symbol)
        } else {
            val book = LegBook(symbol)
            book.add(
                PositionLeg(
                    legId = primaryIds.next(owner, symbol),
                    symbol = symbol,
                    side = if (signedQuantity.signum() > 0) Side.BUY else Side.SELL,
                    quantity = signedQuantity.abs(),
                    entryPrice = avgEntryPrice,
                    openedAt = openedAt,
                    role = LegRole.PRIMARY,
                ),
            )
            books[symbol] = book
        }
        excursions.sync(owner, symbol)
        legBooks.persist(owner, symbol)
        accountIndex.reindex(symbol)
        return true
    }
}
