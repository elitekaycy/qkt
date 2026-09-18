package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.Trade
import java.math.BigDecimal

/**
 * Tracks per-strategy positions. Phase 27: internally backed by [LegBook]s so a single
 * strategy can hold a PRIMARY leg plus N STACK legs on the same symbol simultaneously.
 *
 * The public Position-returning API ([positionFor], [positionsFor], [allByStrategy])
 * continues to return the singular net view — strategies that don't use STACK_AT clauses
 * see no behavior change. The new [legBookFor] accessor exposes the leg-level view for
 * components that need to reason about individual legs (the stack engine, reconciliation).
 *
 * Stack legs are added via [addStackLeg] — they bypass the [apply] averaging logic which
 * would otherwise commingle them into the primary's entry-price math.
 */
class StrategyPositionTracker private constructor(
    private val persistor: com.qkt.persistence.StatePersistor, // read reflectively by BacktestPersistenceInvariantTest
    excursionPersistIntervalMs: Long,
    clock: () -> Long,
    private val legBooks: StrategyLegBooks,
) : StrategyLegReads by legBooks {
    /**
     * [excursionPersistIntervalMs] is the minimum spacing between excursion saves for one
     * (strategy, symbol). A new extreme inside the window is kept in memory and lands with the
     * next save; 0 saves every new extreme.
     */
    constructor(
        persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
        excursionPersistIntervalMs: Long = 1_000L,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(persistor, excursionPersistIntervalMs, clock, StrategyLegBooks(persistor))

    private val log = org.slf4j.LoggerFactory.getLogger(StrategyPositionTracker::class.java)

    private val accountIndex = AccountNetIndex(legBooks)

    private val excursions = PrimaryExcursions(legBooks, persistor, excursionPersistIntervalMs, clock)

    private val primaryIds = PrimaryLegIds()

    private val netting = PrimaryNetting(legBooks, primaryIds)

    private val legFills = LegIntentFills(legBooks, excursions)

    /** The account's positions as a read-only projection of this ledger. */
    val account: LegExposureProvider = AccountPositionView(accountIndex)

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
        ticket: String? = null,
        strategyId: String? = null,
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

    /**
     * Phase 29a: rebuild a [LegBook] from on-disk state. Called at deploy time so a
     * restarted daemon resumes with its prior leg metadata (PRIMARY/STACK roles,
     * parentLegId linkage) intact. No-op when the persistor has no record for this
     * (strategyId, symbol).
     */
    fun preloadFromPersistor(
        strategyId: String,
        symbol: String,
    ) {
        val book = legBooks.restore(strategyId, symbol) ?: return
        accountIndex.reindex(symbol)
        // The restored book needs its excursion tracker like any other (#1158), seeded with the
        // marks saved before the restart when they belong to the same leg.
        excursions.sync(strategyId, symbol)
        excursions.restore(strategyId, symbol, book)
    }

    /**
     * Extend the tracked excursion on [symbol] with bars printed while the daemon was down.
     * Only bars that started after the tracked leg opened count; the bar spanning the entry is
     * skipped because it also holds pre-entry prices. Bars are mid-based, like [onTick].
     */
    fun extendExcursion(
        strategyId: String,
        symbol: String,
        candles: List<com.qkt.marketdata.Candle>,
    ) = excursions.extend(strategyId, symbol, candles)

    /** How one execution slice landed in the leg book. */
    enum class LegAction {
        /** The slice opened (or extended) a specific leg. */
        OPENED,

        /** The slice closed (or reduced) a specific leg, realizing that leg's PnL. */
        CLOSED,

        /** The slice netted into the PRIMARY book — no single-leg attribution. */
        NETTED,
    }

    /**
     * Result of applying one execution slice: the realized PnL plus, when the slice was
     * leg-routed, which leg it touched and how. [unbooked] means nothing was booked — a
     * re-report of an execution the book already holds, or a close naming a leg the book does
     * not hold — and the caller must not account it.
     */
    data class FillApplication(
        val realized: BigDecimal,
        val legId: String? = null,
        val legAction: LegAction = LegAction.NETTED,
        val unbooked: Boolean = false,
    )

    /** Apply an execution slice under [intent]; see [applyFillDetailed]. */
    fun applyFill(
        event: BrokerEvent.OrderFilled,
        intent: LegIntent,
        cumulativeFilled: BigDecimal? = null,
    ): BigDecimal = applyFillDetailed(event, intent, cumulativeFilled).realized

    /**
     * Book one execution slice. [intent] is the leg intent carried by the order (or resolved by
     * the venue ticket); [cumulativeFilled] is the order's total executed quantity including
     * this slice when the venue reports it, which is what makes a re-report of an already
     * booked slice a no-op instead of new quantity (#1096).
     */
    fun applyFillDetailed(
        event: BrokerEvent.OrderFilled,
        intent: LegIntent,
        cumulativeFilled: BigDecimal? = null,
    ): FillApplication {
        if (event.strategyId.isBlank()) return FillApplication(Money.ZERO)
        val application =
            when (intent) {
                is LegIntent.Open -> legFills.open(event, intent, cumulativeFilled)
                is LegIntent.Close -> legFills.close(event, intent)
                LegIntent.Net -> netIntoPrimary(event)
                LegIntent.Unplanned ->
                    error("execution ${event.clientOrderId} for ${event.strategyId} reached the ledger unplanned")
            }
        if (!application.unbooked) {
            legBooks.persist(event.strategyId, event.symbol)
            accountIndex.reindex(event.symbol)
        }
        return application
    }

    /**
     * Drive the per-PRIMARY MFE trackers with a market tick. Called by the runtime on
     * every [com.qkt.events.TickEvent]; cheap when there are no positions on the symbol.
     */
    fun onTick(
        symbol: String,
        price: BigDecimal,
    ) = excursions.onTick(symbol, price)

    /**
     * Current MFE of the PRIMARY leg on [symbol] for [strategyId], or null if no primary
     * exists. Backs the DSL accessor `POSITION.<stream>.mfe`.
     */
    fun primaryMfeFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal? = excursions.mfeFor(strategyId, symbol)

    /**
     * Current MAE of the PRIMARY leg on [symbol] for [strategyId], or null if no primary
     * exists. Backs the DSL accessor `POSITION.<stream>.mae`.
     */
    fun primaryMaeFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal? = excursions.maeFor(strategyId, symbol)

    internal fun primaryAdverseExtremePriceFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal? = excursions.adverseExtremePriceFor(strategyId, symbol)

    private fun netIntoPrimary(event: BrokerEvent.OrderFilled): FillApplication {
        val trade =
            Trade(
                orderId = event.clientOrderId,
                symbol = event.symbol,
                price = event.price,
                quantity = event.quantity,
                side = event.side,
                timestamp = event.timestamp,
            )
        val realized = apply(event.strategyId, trade, event.brokerOrderId)
        excursions.sync(event.strategyId, event.symbol)
        return FillApplication(realized)
    }

    /**
     * Net [trade] into the strategy's PRIMARY leg on its symbol — the netting-venue booking
     * rule: same side averages in, the opposite side realizes, reduces, flat-closes or flips.
     */
    fun apply(
        strategyId: String,
        trade: Trade,
        brokerTicket: String? = null,
    ): BigDecimal {
        val realized = netting.net(strategyId, trade, brokerTicket)
        accountIndex.reindex(trade.symbol)
        return realized
    }

    /**
     * Add a STACK leg directly. Used by the stack engine when a `STACK_AT` clause fires —
     * the resulting fill must NOT be averaged into the primary by [apply].
     */
    fun addStackLeg(
        strategyId: String,
        leg: PositionLeg,
    ) {
        require(leg.role == LegRole.STACK) { "addStackLeg requires LegRole.STACK; got ${leg.role}" }
        val books = legBooks.booksOrCreate(strategyId)
        val book = books.getOrPut(leg.symbol) { LegBook(leg.symbol) }
        book.add(leg)
        legBooks.persist(strategyId, leg.symbol)
        accountIndex.reindex(leg.symbol)
    }

    /**
     * Attach an [LegRole.INDEPENDENT] leg directly. Used by deploy-time reconciliation to adopt a
     * broker position that has no matching persisted leg: an INDEPENDENT leg carrying the venue
     * [PositionLeg.brokerTicket] can be flattened per-leg by close-by-ticket, whereas a STACK leg
     * (or any ticketless leg) only closes via a net opposite order — which on a hedging account
     * opens a counter position instead of closing, the back-to-back hedge-accumulation failure.
     */
    fun addIndependentLeg(
        strategyId: String,
        leg: PositionLeg,
    ) {
        require(leg.role == LegRole.INDEPENDENT) { "addIndependentLeg requires LegRole.INDEPENDENT; got ${leg.role}" }
        val books = legBooks.booksOrCreate(strategyId)
        val book = books.getOrPut(leg.symbol) { LegBook(leg.symbol) }
        book.add(leg)
        legBooks.persist(strategyId, leg.symbol)
        accountIndex.reindex(leg.symbol)
    }

    /**
     * Close a specific leg by id. Used when a STACK leg's own bracket fires, or when
     * external reconciliation closes a position. Returns the closed leg, or null if not found.
     */
    fun closeLeg(
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
