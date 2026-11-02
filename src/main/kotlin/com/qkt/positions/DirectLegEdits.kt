package com.qkt.positions

/**
 * Leg edits that do not come from an execution slice: a STACK or adopted INDEPENDENT leg placed
 * straight into its book, or a leg closed by id. Each edit persists the touched book and
 * reindexes the account, exactly as a fill would. Holds no state of its own; only
 * [StrategyPositionTracker] calls it.
 */
internal class DirectLegEdits(
    private val legBooks: StrategyLegBooks,
    private val accountIndex: AccountNetIndex,
    private val excursions: PrimaryExcursions,
) {
    /**
     * A split of [ratio] new shares per old on [symbol]: every strategy's legs on it hold [ratio] times the quantity
     * at the entry price divided by [ratio], so cost and P&L are unchanged; excursions are rescaled with them.
     */
    fun restateForSplit(
        symbol: String,
        ratio: java.math.BigDecimal,
    ) {
        require(ratio.signum() > 0) { "split ratio on $symbol must be positive: $ratio" }
        for ((strategyId, books) in legBooks.holdersOf(symbol)) {
            books[symbol]?.restate { leg ->
                leg.copy(
                    quantity = leg.quantity.multiply(ratio, com.qkt.common.Money.CONTEXT),
                    entryPrice = leg.entryPrice.divide(ratio, com.qkt.common.Money.CONTEXT),
                )
            }
            legBooks.persist(strategyId, symbol)
        }
        excursions.rescale(symbol, ratio)
        accountIndex.reindex(symbol)
    }

    fun add(
        strategyId: String,
        leg: PositionLeg,
    ) {
        val books = legBooks.booksOrCreate(strategyId)
        val book = books.getOrPut(leg.symbol) { LegBook(leg.symbol) }
        book.add(leg)
        legBooks.persist(strategyId, leg.symbol)
        accountIndex.reindex(leg.symbol)
    }

    fun close(
        strategyId: String,
        symbol: String,
        legId: String,
    ): PositionLeg? {
        val book = legBooks.book(strategyId, symbol) ?: return null
        val closed = book.close(legId)
        if (book.isEmpty()) {
            legBooks.booksOf(strategyId)?.remove(symbol)
        }
        legBooks.persist(strategyId, symbol)
        accountIndex.reindex(symbol)
        return closed
    }
}
