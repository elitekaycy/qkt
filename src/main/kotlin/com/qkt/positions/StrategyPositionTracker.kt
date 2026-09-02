package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
class StrategyPositionTracker(
    private val persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
) {
    private val log = org.slf4j.LoggerFactory.getLogger(StrategyPositionTracker::class.java)

    private val byStrategy: MutableMap<String, MutableMap<String, LegBook>> = ConcurrentHashMap()

    /**
     * Account-level net position per symbol, folded across strategies. Rebuilt for one symbol
     * after every mutation of that symbol's legs, so account reads are index lookups and the
     * account never disagrees with the ledger it is derived from.
     */
    private val accountBySymbol: MutableMap<String, Position> = ConcurrentHashMap()

    /** The account's positions as a read-only projection of this ledger. */
    val account: LegExposureProvider = AccountPositionView(this)

    internal fun accountPositionFor(symbol: String): Position? = accountBySymbol[symbol]

    internal fun accountPositions(): Map<String, Position> = accountBySymbol.toMap()

    internal fun accountSymbols(): Set<String> = accountBySymbol.keys

    internal fun forEachLeg(
        symbol: String,
        action: (PositionLeg) -> Unit,
    ) {
        for (books in byStrategy.values) {
            val book = books[symbol] ?: continue
            book.forEach(action)
        }
    }

    private fun reindex(symbol: String) {
        // Accumulate from a scale-0 zero so the net keeps the legs' own quantity scale, exactly
        // as the strategy net view does — report columns print 0.01, not 0.01000000.
        var netQty = BigDecimal.ZERO
        var earliest = Long.MAX_VALUE
        var any = false
        for (books in byStrategy.values) {
            val book = books[symbol] ?: continue
            book.forEach { leg ->
                any = true
                netQty = if (leg.side == Side.BUY) netQty.add(leg.quantity) else netQty.subtract(leg.quantity)
                if (leg.openedAt < earliest) earliest = leg.openedAt
            }
        }
        if (!any) {
            accountBySymbol.remove(symbol)
            return
        }
        if (netQty.signum() == 0) {
            accountBySymbol[symbol] = Position(symbol, Money.ZERO, Money.ZERO, openedAt = earliest)
            return
        }
        val netSide = if (netQty.signum() > 0) Side.BUY else Side.SELL
        var notional = Money.ZERO
        var qty = Money.ZERO
        for (books in byStrategy.values) {
            val book = books[symbol] ?: continue
            book.forEach { leg ->
                if (leg.side != netSide) return@forEach
                notional = notional.add(leg.entryPrice.multiply(leg.quantity))
                qty = qty.add(leg.quantity)
            }
        }
        val avg = notional.divide(qty, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
        accountBySymbol[symbol] = Position(symbol, netQty, avg, openedAt = earliest)
    }

    /**
     * Replace a symbol's ledger with the venue's net position when the venue reports a
     * correction. Only possible when exactly one strategy trades the symbol — a net figure
     * cannot be attributed across several — otherwise the correction is logged and left to the
     * per-ticket reconcile. Returns true when the ledger changed.
     */
    fun reconcileNet(
        symbol: String,
        signedQuantity: BigDecimal,
        avgEntryPrice: BigDecimal,
        openedAt: Long,
        source: String,
    ): Boolean {
        val owners = byStrategy.entries.filter { (_, books) -> books.containsKey(symbol) }
        if (owners.size > 1) {
            log.warn("venue correction for {} from {} not applied: {} strategies hold it", symbol, source, owners.size)
            return false
        }
        val strategyId = owners.singleOrNull()?.key
        if (strategyId == null) {
            if (signedQuantity.signum() == 0) return false
            log.warn("venue correction for {} from {} not applied: no strategy holds it", symbol, source)
            return false
        }
        val books = byStrategy.getValue(strategyId)
        if (signedQuantity.signum() == 0) {
            books.remove(symbol)
        } else {
            val book = LegBook(symbol)
            book.add(
                PositionLeg(
                    legId = nextPrimaryId(strategyId, symbol),
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
        syncPrimaryMfeTracker(strategyId, symbol)
        persistBook(strategyId, symbol)
        reindex(symbol)
        return true
    }

    private fun persistBook(
        strategyId: String,
        symbol: String,
    ) {
        val book = byStrategy[strategyId]?.get(symbol) ?: LegBook(symbol)
        runCatching { persistor.saveLegBook(strategyId, symbol, book) }
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
        val persisted = runCatching { persistor.loadLegBook(strategyId, symbol) }.getOrNull() ?: return
        if (persisted.legs.isEmpty()) return
        val books = byStrategy.getOrPut(strategyId) { ConcurrentHashMap() }
        val book = books.getOrPut(symbol) { LegBook(symbol) }
        for (leg in persisted.legs) book.add(leg.toPositionLeg())
        reindex(symbol)
    }

    /** Monotonic counter for engine-internal PRIMARY leg ids. */
    private val primaryLegSeq = AtomicLong()

    /**
     * Per-(strategyId, symbol) excursion trackers for the current PRIMARY leg. Maintained in
     * sync with the leg-book by [syncPrimaryMfeTracker] after every fill, and updated on
     * each market tick via [onTick]. Reads land via [primaryMfeFor], which backs the DSL
     * accessor `POSITION.<stream>.mfe`, and [primaryMaeFor], which backs
     * `POSITION.<stream>.mae`.
     *
     * Same-direction averaging fills re-anchor the tracker to the new weighted entry —
     * MFE resets to zero from the new reference point, matching the "favorable excursion
     * from current best-estimate entry" semantic.
     */
    private val primaryMfeTrackers: MutableMap<Pair<String, String>, LegMfe> = ConcurrentHashMap()

    private data class LegMfe(
        val legId: String,
        val tracker: MfeTracker,
    )

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
                is LegIntent.Open -> openLeg(event, intent, cumulativeFilled)
                is LegIntent.Close -> closeLeg(event, intent)
                LegIntent.Net -> netIntoPrimary(event)
                LegIntent.Unplanned ->
                    error("execution ${event.clientOrderId} for ${event.strategyId} reached the ledger unplanned")
            }
        if (!application.unbooked) {
            persistBook(event.strategyId, event.symbol)
            reindex(event.symbol)
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
    ) {
        if (primaryMfeTrackers.isEmpty()) return
        for ((key, lm) in primaryMfeTrackers) {
            if (key.second == symbol) lm.tracker.onTick(price)
        }
    }

    /**
     * Current MFE of the PRIMARY leg on [symbol] for [strategyId], or null if no primary
     * exists. Backs the DSL accessor `POSITION.<stream>.mfe`.
     */
    fun primaryMfeFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal? = primaryMfeTrackers[Pair(strategyId, symbol)]?.tracker?.value()

    /**
     * Current MAE of the PRIMARY leg on [symbol] for [strategyId], or null if no primary
     * exists. Backs the DSL accessor `POSITION.<stream>.mae`.
     */
    fun primaryMaeFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal? = primaryMfeTrackers[Pair(strategyId, symbol)]?.tracker?.mae()

    internal fun primaryAdverseExtremePriceFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal? = primaryMfeTrackers[Pair(strategyId, symbol)]?.tracker?.adverseExtremePrice()

    private fun syncPrimaryMfeTracker(
        strategyId: String,
        symbol: String,
    ) {
        val key = Pair(strategyId, symbol)
        val primary = byStrategy[strategyId]?.get(symbol)?.primary()
        if (primary == null) {
            primaryMfeTrackers.remove(key)
            return
        }
        val existing = primaryMfeTrackers[key]
        if (existing == null || existing.legId != primary.legId) {
            primaryMfeTrackers[key] = LegMfe(primary.legId, MfeTracker(primary.side, primary.entryPrice))
        }
    }

    private fun openLeg(
        event: BrokerEvent.OrderFilled,
        intent: LegIntent.Open,
        cumulativeFilled: BigDecimal?,
    ): FillApplication {
        val books = byStrategy.getOrPut(event.strategyId) { ConcurrentHashMap() }
        val book = books.getOrPut(event.symbol) { LegBook(event.symbol) }
        val ticket = event.brokerOrderId?.takeIf { it.isNotBlank() }
        // A venue ticket is one position and belongs to exactly one leg. A second leg claiming
        // it is a duplicate report (a replayed execution attributed to another order), not new
        // exposure.
        val owner = ticket?.let { book.legByTicket(it) }
        if (owner != null && owner.legId != intent.legId) {
            log.warn(
                "duplicate execution ignored: ticket {} on {} is already leg {} — {} for order {} books nothing",
                ticket,
                event.symbol,
                owner.legId,
                event.strategyId,
                event.clientOrderId,
            )
            return FillApplication(Money.ZERO, owner.legId, LegAction.OPENED, unbooked = true)
        }
        val existing = book.leg(intent.legId)
        if (existing == null) {
            book.add(
                PositionLeg(
                    legId = intent.legId,
                    symbol = event.symbol,
                    side = event.side,
                    quantity = event.quantity,
                    entryPrice = event.price,
                    openedAt = event.timestamp,
                    role = intent.role,
                    parentLegId = intent.parentLegId,
                    brokerTicket = ticket,
                ),
            )
            return FillApplication(Money.ZERO, intent.legId, LegAction.OPENED)
        }
        // The same order executing again: book only what the venue reports beyond what the
        // leg already holds. A cumulative at or below the booked quantity is a re-report.
        val sliceQuantity =
            if (cumulativeFilled != null && ticket != null && existing.brokerTicket == ticket) {
                val delta = cumulativeFilled.subtract(existing.quantity)
                if (delta.signum() <= 0) {
                    log.warn(
                        "duplicate execution ignored: leg {} on {} already holds {} of ticket {} (reported cumulative {})",
                        existing.legId,
                        event.symbol,
                        existing.quantity.toPlainString(),
                        ticket,
                        cumulativeFilled.toPlainString(),
                    )
                    return FillApplication(Money.ZERO, existing.legId, LegAction.OPENED, unbooked = true)
                }
                delta
            } else {
                event.quantity
            }
        mergeOwnedOpenSlice(
            book,
            existing.copy(
                quantity = sliceQuantity,
                entryPrice = event.price,
                openedAt = event.timestamp,
                brokerTicket = ticket,
            ),
        )
        return FillApplication(Money.ZERO, existing.legId, LegAction.OPENED)
    }

    /** Merge another execution slice into one stable owned leg without scanning the book. */
    private fun mergeOwnedOpenSlice(
        book: LegBook,
        slice: PositionLeg,
    ) {
        val existing = book.leg(slice.legId)
        if (existing == null) {
            book.add(slice)
            return
        }
        require(existing.symbol == slice.symbol && existing.side == slice.side) {
            "owned leg ${slice.legId} changed symbol or side across execution slices"
        }
        require(existing.role == slice.role && existing.parentLegId == slice.parentLegId) {
            "owned leg ${slice.legId} changed ownership across execution slices"
        }
        require(
            existing.brokerTicket == null ||
                slice.brokerTicket == null ||
                existing.brokerTicket == slice.brokerTicket,
        ) {
            "owned leg ${slice.legId} changed broker ticket across execution slices"
        }
        val totalQuantity = existing.quantity.add(slice.quantity)
        val averagePrice =
            existing.entryPrice
                .multiply(existing.quantity)
                .add(slice.entryPrice.multiply(slice.quantity))
                .divide(totalQuantity, Money.CONTEXT)
                .setScale(Money.SCALE, Money.ROUNDING)
        book.close(existing.legId)
        book.add(
            existing.copy(
                quantity = totalQuantity,
                entryPrice = averagePrice,
                brokerTicket = existing.brokerTicket ?: slice.brokerTicket,
            ),
        )
    }

    /**
     * Close (or reduce) the one leg [intent] names — by qkt id first, then by venue ticket.
     */
    private fun closeLeg(
        event: BrokerEvent.OrderFilled,
        intent: LegIntent.Close,
    ): FillApplication {
        val book = byStrategy[event.strategyId]?.get(event.symbol)
        val leg =
            book?.let { b ->
                intent.legId?.let { b.leg(it) } ?: intent.ticket?.let { b.legByTicket(it) }
            }
        if (book == null || leg == null) {
            // A close cannot create exposure: booking it against another leg would invent a
            // position the venue never opened. Nothing is booked; venue reconciliation owns the
            // correction.
            log.error(
                "close for {} on {} names leg {} / ticket {} the book does not hold; nothing booked",
                event.strategyId,
                event.symbol,
                intent.legId,
                intent.ticket,
            )
            return FillApplication(Money.ZERO, unbooked = true)
        }
        val closed = book.close(leg.legId) ?: return FillApplication(Money.ZERO, unbooked = true)
        val closingQty = closed.quantity.min(event.quantity)
        val priceDiff =
            if (closed.side == Side.BUY) {
                event.price.subtract(closed.entryPrice)
            } else {
                closed.entryPrice.subtract(event.price)
            }
        val realized = closingQty.multiply(priceDiff).setScale(Money.SCALE, Money.ROUNDING)
        val remaining = closed.quantity.subtract(closingQty)
        if (remaining.signum() > 0) book.add(closed.copy(quantity = remaining))
        if (book.isEmpty()) byStrategy[event.strategyId]?.remove(event.symbol)
        if (closed.role == LegRole.PRIMARY) syncPrimaryMfeTracker(event.strategyId, event.symbol)
        return FillApplication(realized, closed.legId, LegAction.CLOSED)
    }

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
        syncPrimaryMfeTracker(event.strategyId, event.symbol)
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
        val realized = applyNet(strategyId, trade, brokerTicket)
        reindex(trade.symbol)
        return realized
    }

    private fun applyNet(
        strategyId: String,
        trade: Trade,
        brokerTicket: String?,
    ): BigDecimal {
        val books = byStrategy.getOrPut(strategyId) { ConcurrentHashMap() }
        val book = books.getOrPut(trade.symbol) { LegBook(trade.symbol) }
        val primary = book.primary()

        // No primary yet → open one with this trade.
        if (primary == null) {
            book.add(
                PositionLeg(
                    legId = nextPrimaryId(strategyId, trade.symbol),
                    symbol = trade.symbol,
                    side = trade.side,
                    quantity = trade.quantity,
                    entryPrice = trade.price,
                    openedAt = trade.timestamp,
                    role = LegRole.PRIMARY,
                    brokerTicket = brokerTicket,
                ),
            )
            return Money.ZERO
        }

        val sameDirection = primary.side == trade.side

        if (sameDirection) {
            // Average into the existing primary. Replace the leg with one carrying the
            // combined quantity + weighted entry, preserving openedAt.
            val totalQty = primary.quantity.add(trade.quantity)
            val newAvg =
                primary.entryPrice
                    .multiply(primary.quantity)
                    .add(trade.price.multiply(trade.quantity))
                    .divide(totalQty, Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            book.close(primary.legId)
            book.add(
                PositionLeg(
                    legId = nextPrimaryId(strategyId, trade.symbol),
                    symbol = trade.symbol,
                    side = primary.side,
                    quantity = totalQty,
                    entryPrice = newAvg,
                    openedAt = primary.openedAt,
                    role = LegRole.PRIMARY,
                    brokerTicket = if (primary.brokerTicket == brokerTicket) brokerTicket else null,
                ),
            )
            return Money.ZERO
        }

        // Opposite direction → realize PnL on the closed portion, then either reduce,
        // flat-close, or flip the primary.
        val closingQty = primary.quantity.min(trade.quantity)
        val priceDiff =
            if (primary.side == Side.BUY) {
                trade.price.subtract(primary.entryPrice)
            } else {
                primary.entryPrice.subtract(trade.price)
            }
        val realized = closingQty.multiply(priceDiff).setScale(Money.SCALE, Money.ROUNDING)

        book.close(primary.legId)
        val remainingPrimaryQty = primary.quantity.subtract(trade.quantity)
        when {
            remainingPrimaryQty.signum() == 0 -> {
                // Fully closed — primary removed, nothing to add.
            }
            remainingPrimaryQty.signum() > 0 -> {
                // Reduced — same side and entry price preserved.
                book.add(
                    PositionLeg(
                        legId = nextPrimaryId(strategyId, trade.symbol),
                        symbol = trade.symbol,
                        side = primary.side,
                        quantity = remainingPrimaryQty,
                        entryPrice = primary.entryPrice,
                        openedAt = primary.openedAt,
                        role = LegRole.PRIMARY,
                        brokerTicket = primary.brokerTicket,
                    ),
                )
            }
            else -> {
                // Flipped — new primary on opposite side with the remainder and the trade price.
                book.add(
                    PositionLeg(
                        legId = nextPrimaryId(strategyId, trade.symbol),
                        symbol = trade.symbol,
                        side = trade.side,
                        quantity = remainingPrimaryQty.abs(),
                        entryPrice = trade.price,
                        openedAt = trade.timestamp,
                        role = LegRole.PRIMARY,
                        brokerTicket = brokerTicket,
                    ),
                )
            }
        }
        if (book.isEmpty()) {
            books.remove(trade.symbol)
        }
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
        val books = byStrategy.getOrPut(strategyId) { ConcurrentHashMap() }
        val book = books.getOrPut(leg.symbol) { LegBook(leg.symbol) }
        book.add(leg)
        persistBook(strategyId, leg.symbol)
        reindex(leg.symbol)
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
        val books = byStrategy.getOrPut(strategyId) { ConcurrentHashMap() }
        val book = books.getOrPut(leg.symbol) { LegBook(leg.symbol) }
        book.add(leg)
        persistBook(strategyId, leg.symbol)
        reindex(leg.symbol)
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
        val book = byStrategy[strategyId]?.get(symbol) ?: return null
        val closed = book.close(legId)
        if (book.isEmpty()) {
            byStrategy[strategyId]?.remove(symbol)
        }
        persistBook(strategyId, symbol)
        reindex(symbol)
        return closed
    }

    fun positionFor(
        strategyId: String,
        symbol: String,
    ): Position? = byStrategy[strategyId]?.get(symbol)?.netView()

    fun positionsFor(strategyId: String): Map<String, Position> {
        val books = byStrategy[strategyId] ?: return emptyMap()
        // Single pass straight into the result map; the previous mapNotNull{}.toMap() allocated an
        // intermediate List of Pairs and rehashed, per call, on the per-tick position-read path.
        return buildMap(books.size) {
            for ((sym, book) in books) book.netView()?.let { put(sym, it) }
        }
    }

    fun allByStrategy(): Map<String, Map<String, Position>> =
        byStrategy.mapValues { (_, books) ->
            books.mapNotNull { (sym, book) -> book.netView()?.let { sym to it } }.toMap()
        }

    /** Immutable snapshot of every open leg owned by [strategyId], across symbols. */
    fun allLegsFor(strategyId: String): List<PositionLeg> =
        byStrategy[strategyId]?.values?.flatMap { it.all() } ?: emptyList()

    /** New Phase 27 accessor: the full leg book for direct inspection. */
    fun legBookFor(
        strategyId: String,
        symbol: String,
    ): LegBook? = byStrategy[strategyId]?.get(symbol)

    /** Find an open leg by id across every symbol the strategy holds. */
    fun legById(
        strategyId: String,
        legId: String,
    ): PositionLeg? =
        byStrategy[strategyId]
            ?.values
            ?.firstNotNullOfOrNull { book -> book.all().firstOrNull { it.legId == legId } }

    /**
     * Venue ticket of the leg with [legId] for [strategyId], searching across that strategy's
     * symbols, or null if no such leg (or it has no ticket). Lets an engine-fired exit close the
     * exact venue position by ticket — e.g. a trailing stop closing its independent straddle leg.
     */
    fun ticketForLeg(
        strategyId: String,
        legId: String,
    ): String? =
        byStrategy[strategyId]?.values?.firstNotNullOfOrNull { book ->
            book.all().firstOrNull { it.legId == legId }?.brokerTicket
        }

    /** Venue ticket for the strategy's PRIMARY position on [symbol], when unambiguous. */
    fun ticketForPrimary(
        strategyId: String,
        symbol: String,
    ): String? = byStrategy[strategyId]?.get(symbol)?.primary()?.brokerTicket

    /** Open position count on [symbol] for [strategyId] — the real number of legs, not the net. */
    fun openCountFor(
        strategyId: String,
        symbol: String,
    ): Int = byStrategy[strategyId]?.get(symbol)?.size() ?: 0

    /** Open long-side legs on [symbol] for [strategyId]. */
    fun longCountFor(
        strategyId: String,
        symbol: String,
    ): Int = byStrategy[strategyId]?.get(symbol)?.longCount() ?: 0

    /** Open short-side legs on [symbol] for [strategyId]. */
    fun shortCountFor(
        strategyId: String,
        symbol: String,
    ): Int = byStrategy[strategyId]?.get(symbol)?.shortCount() ?: 0

    /** Gross exposure (sum of leg sizes, side-blind) on [symbol] for [strategyId]. */
    fun grossFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal = byStrategy[strategyId]?.get(symbol)?.grossQuantity() ?: Money.ZERO

    fun driftFor(
        symbol: String,
        brokerView: PositionProvider,
    ): BigDecimal {
        val strategySum =
            byStrategy.values.fold(Money.ZERO) { acc, books ->
                acc.add(books[symbol]?.netQuantity() ?: Money.ZERO)
            }
        val broker = brokerView.positionFor(symbol)?.quantity ?: Money.ZERO
        return strategySum.subtract(broker).setScale(Money.SCALE, Money.ROUNDING)
    }

    private fun nextPrimaryId(
        strategyId: String,
        symbol: String,
    ): String = "$strategyId-$symbol-primary-${primaryLegSeq.incrementAndGet()}"
}
