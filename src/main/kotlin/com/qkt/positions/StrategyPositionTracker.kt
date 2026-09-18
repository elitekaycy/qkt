package com.qkt.positions

import com.qkt.common.Money
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
    private val legBooks: StrategyLegBooks,
    private val excursions: PrimaryExcursions,
) : StrategyLegReads by legBooks,
    PrimaryExcursionTracking by excursions {
    /**
     * [excursionPersistIntervalMs] is the minimum spacing between excursion saves for one
     * (strategy, symbol). A new extreme inside the window is kept in memory and lands with the
     * next save; 0 saves every new extreme.
     */
    constructor(
        persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
        excursionPersistIntervalMs: Long = 1_000L,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(persistor, StrategyLegBooks(persistor), excursionPersistIntervalMs, clock)

    private constructor(
        persistor: com.qkt.persistence.StatePersistor,
        legBooks: StrategyLegBooks,
        excursionPersistIntervalMs: Long,
        clock: () -> Long,
    ) : this(persistor, legBooks, PrimaryExcursions(legBooks, persistor, excursionPersistIntervalMs, clock))

    private val accountIndex = AccountNetIndex(legBooks)
    private val primaryIds = PrimaryLegIds()
    private val netting = PrimaryNetting(legBooks, primaryIds, accountIndex, excursions)
    private val legFills = LegIntentFills(legBooks, excursions)
    private val reconciler = VenuePositionReconciler(legBooks, primaryIds, excursions, accountIndex)
    private val directEdits = DirectLegEdits(legBooks, accountIndex)

    /** The account's positions as a read-only projection of this ledger. */
    val account: LegExposureProvider = AccountPositionView(accountIndex)

    /**
     * Apply a venue correction to [symbol]'s ledger; true when the ledger changed. The rules
     * (single owner, per-ticket legs, net replacement) are on [VenuePositionReconciler].
     */
    fun reconcileNet(
        symbol: String,
        signedQuantity: BigDecimal,
        avgEntryPrice: BigDecimal,
        openedAt: Long,
        source: String,
        ticket: String? = null,
        strategyId: String? = null,
    ): Boolean = reconciler.reconcileNet(symbol, signedQuantity, avgEntryPrice, openedAt, source, ticket, strategyId)

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
                LegIntent.Net -> netting.netFill(event)
                LegIntent.Unplanned ->
                    error("execution ${event.clientOrderId} for ${event.strategyId} reached the ledger unplanned")
            }
        if (!application.unbooked) {
            legBooks.persist(event.strategyId, event.symbol)
            accountIndex.reindex(event.symbol)
        }
        return application
    }

    internal fun primaryAdverseExtremePriceFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal? = excursions.primaryAdverseExtremePriceFor(strategyId, symbol)

    /**
     * Net [trade] into the strategy's PRIMARY leg on its symbol — the netting-venue booking
     * rule: same side averages in, the opposite side realizes, reduces, flat-closes or flips.
     */
    fun apply(
        strategyId: String,
        trade: Trade,
        brokerTicket: String? = null,
    ): BigDecimal = netting.apply(strategyId, trade, brokerTicket)

    /**
     * Add a STACK leg directly. Used by the stack engine when a `STACK_AT` clause fires —
     * the resulting fill must NOT be averaged into the primary by [apply].
     */
    fun addStackLeg(
        strategyId: String,
        leg: PositionLeg,
    ) {
        require(leg.role == LegRole.STACK) { "addStackLeg requires LegRole.STACK; got ${leg.role}" }
        directEdits.add(strategyId, leg)
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
        directEdits.add(strategyId, leg)
    }

    /**
     * Close a specific leg by id. Used when a STACK leg's own bracket fires, or when
     * external reconciliation closes a position. Returns the closed leg, or null if not found.
     */
    fun closeLeg(
        strategyId: String,
        symbol: String,
        legId: String,
    ): PositionLeg? = directEdits.close(strategyId, symbol, legId)
}
