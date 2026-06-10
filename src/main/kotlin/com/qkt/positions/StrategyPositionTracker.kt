package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
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
    private val byStrategy: MutableMap<String, MutableMap<String, LegBook>> = ConcurrentHashMap()

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
    }

    /** Monotonic counter for engine-internal PRIMARY leg ids. */
    private val primaryLegSeq = AtomicLong()

    /**
     * Per-(strategyId, symbol) MFE trackers for the current PRIMARY leg. Maintained in
     * sync with the leg-book by [syncPrimaryMfeTracker] after every fill, and updated on
     * each market tick via [onTick]. Reads land via [primaryMfeFor], which backs the DSL
     * accessor `POSITION.<stream>.mfe`.
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

    /**
     * Pre-registered stack-leg open intents. Key is `"$strategyId|$clientOrderId"`.
     * When [applyFill] sees a matching clientOrderId, the resulting fill is added as a
     * STACK leg (with the bracket id as legId) instead of being averaged into the
     * primary. Populated by [com.qkt.dsl.compile.StackOrchestrator] at engine emit time.
     */
    private val pendingStackOpens: MutableMap<String, StackOpenIntent> = ConcurrentHashMap()

    /**
     * Pre-registered stack-leg close ids. Key is `"$strategyId|$clientOrderId"`, value
     * is the stack legId to close. Populated for predicted bracket TP/SL ids of stack
     * orders so that those fills close the right stack leg and realize its PnL without
     * touching the primary.
     */
    private val pendingStackCloses: MutableMap<String, String> = ConcurrentHashMap()

    /**
     * Pre-registered independent-leg open ids. Key is `"$strategyId|$clientOrderId"`, value
     * is the [legId] the fill should open as an [LegRole.INDEPENDENT] leg — a standalone
     * position that does NOT net against existing legs (each leg of an OCO_ENTRY straddle is
     * one of these). Populated at order-emit time; closed via [registerStackClose] (which
     * closes any leg by id) when the leg's bracket exit fills.
     */
    private val pendingIndependentOpens: MutableMap<String, String> = ConcurrentHashMap()

    private data class StackOpenIntent(
        val stackLegId: String,
        val parentLegId: String,
    )

    /**
     * Phase 27: declare that a future [BrokerEvent.OrderFilled] with [clientOrderId]
     * should open a STACK leg (not average into PRIMARY). The [stackLegId] becomes the
     * new leg's id and is correlated to [parentLegId] via [PositionLeg.parentLegId].
     */
    fun registerStackOpen(
        strategyId: String,
        clientOrderId: String,
        stackLegId: String,
        parentLegId: String,
    ) {
        pendingStackOpens["$strategyId|$clientOrderId"] = StackOpenIntent(stackLegId, parentLegId)
    }

    /**
     * Phase 27: declare that a future [BrokerEvent.OrderFilled] with [clientOrderId]
     * should close stack leg [stackLegId] (typically a stack-bracket TP or SL fill).
     * Returns realized PnL on that fill via the normal [applyFill] return path.
     */
    fun registerStackClose(
        strategyId: String,
        clientOrderId: String,
        stackLegId: String,
    ) {
        pendingStackCloses["$strategyId|$clientOrderId"] = stackLegId
    }

    /**
     * Declare that a future [BrokerEvent.OrderFilled] with [clientOrderId] should open an
     * [LegRole.INDEPENDENT] leg with id [legId] — a standalone position that coexists with
     * others on the symbol without netting (the truthful-position path; e.g. each leg of an
     * OCO_ENTRY straddle). Close it via [registerStackClose] when its bracket exit fills.
     */
    fun registerIndependentOpen(
        strategyId: String,
        clientOrderId: String,
        legId: String,
    ) {
        pendingIndependentOpens["$strategyId|$clientOrderId"] = legId
    }

    /**
     * Drop any registered-but-unfilled pending intent for [clientOrderId]. Called when an
     * order is cancelled or rejected (e.g. the losing leg of an OCO bracket) — its open/close
     * intent will never be matched by a fill, so without this it would leak in the pending maps
     * for the life of the session.
     */
    fun forgetPending(
        strategyId: String,
        clientOrderId: String,
    ) {
        val key = "$strategyId|$clientOrderId"
        pendingStackOpens.remove(key)
        pendingStackCloses.remove(key)
        pendingIndependentOpens.remove(key)
    }

    fun applyFill(event: BrokerEvent.OrderFilled): BigDecimal {
        if (event.strategyId.isBlank()) return Money.ZERO

        val key = "${event.strategyId}|${event.clientOrderId}"

        pendingStackOpens.remove(key)?.let { intent ->
            val realized = applyStackOpen(event, intent)
            persistBook(event.strategyId, event.symbol)
            return realized
        }
        pendingStackCloses.remove(key)?.let { stackLegId ->
            val realized = applyStackClose(event, stackLegId)
            persistBook(event.strategyId, event.symbol)
            return realized
        }
        pendingIndependentOpens.remove(key)?.let { legId ->
            applyIndependentOpen(event, legId)
            persistBook(event.strategyId, event.symbol)
            return Money.ZERO
        }
        // A venue-detected close of a leg held with attached SL/TP arrives under the entry's id
        // (the position poller), not a registered exit id, so it isn't caught above. Match it to
        // the leg by its venue ticket and realize that leg, instead of netting.
        closeLegByTicket(event)?.let { realized ->
            persistBook(event.strategyId, event.symbol)
            return realized
        }

        val trade =
            Trade(
                orderId = event.clientOrderId,
                symbol = event.symbol,
                price = event.price,
                quantity = event.quantity,
                side = event.side,
                timestamp = event.timestamp,
            )
        val realized = apply(event.strategyId, trade)
        syncPrimaryMfeTracker(event.strategyId, trade.symbol)
        persistBook(event.strategyId, event.symbol)
        return realized
    }

    /**
     * Drive the per-PRIMARY MFE trackers with a market tick. Called by the runtime on
     * every [com.qkt.events.TickEvent]; cheap when there are no positions on the symbol.
     */
    fun onTick(
        symbol: String,
        price: BigDecimal,
    ) {
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

    private fun applyStackOpen(
        event: BrokerEvent.OrderFilled,
        intent: StackOpenIntent,
    ): BigDecimal {
        val books = byStrategy.getOrPut(event.strategyId) { ConcurrentHashMap() }
        val book = books.getOrPut(event.symbol) { LegBook(event.symbol) }
        book.add(
            PositionLeg(
                legId = intent.stackLegId,
                symbol = event.symbol,
                side = event.side,
                quantity = event.quantity,
                entryPrice = event.price,
                openedAt = event.timestamp,
                role = LegRole.STACK,
                parentLegId = intent.parentLegId,
                brokerTicket = event.brokerOrderId,
            ),
        )
        return Money.ZERO
    }

    private fun applyIndependentOpen(
        event: BrokerEvent.OrderFilled,
        legId: String,
    ) {
        val books = byStrategy.getOrPut(event.strategyId) { ConcurrentHashMap() }
        val book = books.getOrPut(event.symbol) { LegBook(event.symbol) }
        book.add(
            PositionLeg(
                legId = legId,
                symbol = event.symbol,
                side = event.side,
                quantity = event.quantity,
                entryPrice = event.price,
                openedAt = event.timestamp,
                role = LegRole.INDEPENDENT,
                brokerTicket = event.brokerOrderId,
            ),
        )
    }

    /**
     * Close the leg whose venue ticket matches this close fill's
     * [BrokerEvent.OrderFilled.brokerOrderId], realizing its PnL at the close price. Matches any
     * ticketed leg — an [LegRole.INDEPENDENT] straddle leg or a [LegRole.STACK] tier — since both
     * are held with venue-attached SL/TP and close by ticket. Returns the realized PnL, or null if
     * no such leg (so the caller falls through to the netting path). PRIMARY legs carry no ticket,
     * so they never match here.
     */
    private fun closeLegByTicket(event: BrokerEvent.OrderFilled): BigDecimal? {
        val ticket = event.brokerOrderId?.takeIf { it.isNotBlank() } ?: return null
        val book = byStrategy[event.strategyId]?.get(event.symbol) ?: return null
        val leg =
            book.all().firstOrNull {
                it.brokerTicket == ticket
            } ?: return null
        val closed = book.close(leg.legId) ?: return null
        val priceDiff =
            if (closed.side == Side.BUY) {
                event.price.subtract(closed.entryPrice)
            } else {
                closed.entryPrice.subtract(event.price)
            }
        val realized = closed.quantity.multiply(priceDiff).setScale(Money.SCALE, Money.ROUNDING)
        if (book.isEmpty()) byStrategy[event.strategyId]?.remove(event.symbol)
        return realized
    }

    private fun applyStackClose(
        event: BrokerEvent.OrderFilled,
        stackLegId: String,
    ): BigDecimal {
        val book = byStrategy[event.strategyId]?.get(event.symbol) ?: return Money.ZERO
        val closed = book.close(stackLegId) ?: return Money.ZERO
        val priceDiff =
            if (closed.side == Side.BUY) {
                event.price.subtract(closed.entryPrice)
            } else {
                closed.entryPrice.subtract(event.price)
            }
        val realized = closed.quantity.multiply(priceDiff).setScale(Money.SCALE, Money.ROUNDING)
        if (book.isEmpty()) {
            byStrategy[event.strategyId]?.remove(event.symbol)
        }
        return realized
    }

    fun apply(
        strategyId: String,
        trade: Trade,
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
        return closed
    }

    fun positionFor(
        strategyId: String,
        symbol: String,
    ): Position? = byStrategy[strategyId]?.get(symbol)?.netView()

    fun positionsFor(strategyId: String): Map<String, Position> =
        byStrategy[strategyId]
            ?.mapNotNull { (sym, book) -> book.netView()?.let { sym to it } }
            ?.toMap()
            ?: emptyMap()

    fun allByStrategy(): Map<String, Map<String, Position>> =
        byStrategy.mapValues { (_, books) ->
            books.mapNotNull { (sym, book) -> book.netView()?.let { sym to it } }.toMap()
        }

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
