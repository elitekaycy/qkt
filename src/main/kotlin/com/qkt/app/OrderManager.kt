package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.compile.BracketPercent
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.At
import com.qkt.execution.ExpiryAction
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.LegIntent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.execution.TriggerType
import com.qkt.execution.exitLegIntent
import com.qkt.execution.isCompositeShape
import com.qkt.execution.isTerminal
import com.qkt.execution.withCloseTicket
import com.qkt.execution.withStrategyId
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.Tick
import com.qkt.marketdata.buyExecPrice
import com.qkt.marketdata.sellExecPrice
import com.qkt.positions.LegRole
import com.qkt.positions.PendingOrderExposureProvider
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Manages the lifecycle of every order from signal to fill.
 *
 * Translates [com.qkt.strategy.Signal]s into [com.qkt.execution.OrderRequest]s, splits
 * engine-managed shapes (Bracket, ScaleOut, TimeExit, Stack) into atomic broker calls,
 * tracks the [com.qkt.execution.ManagedOrder] state machine through broker callbacks,
 * and emits trade events for downstream consumers.
 *
 * One per [LiveSession] / `Backtest` run; not thread-safe.
 */
class OrderManager(
    private val broker: Broker,
    private val bus: EventBus,
    private val priceProvider: MarketPriceProvider,
    private val clock: Clock,
    private val persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
    /**
     * Resolves an engine-managed exit's clientOrderId to the venue ticket of the position it
     * closes, or null when there's no such ticketed position (a plain netting close). Lets a
     * fired trailing stop close its position by ticket on a hedging account instead of opening a
     * counter. Wired by [TradingPipeline] to the position tracker; null in tests/backtest.
     */
    private val closeTicketFor: ((String, String) -> String?)? = null,
    /** Fallback resolver for a plain bracket whose armed trail closes the PRIMARY position. */
    private val closePrimaryTicketFor: ((String, String) -> String?)? = null,
    /** Live hedging sessions must never turn a missing close ticket into an opposite market order. */
    private val requireArmedTrailTicket: Boolean = false,
    /** Venue metadata used to report bracket risk in account units (`price distance x qty x contractSize`). */
    private val instruments: InstrumentRegistry = NoopInstrumentRegistry,
    /**
     * Record per-bracket risk into [riskByClientOrderId] for the backtest report to read via
     * [riskUsdFor]. Only the backtest path consumes it, so live leaves this false — otherwise the
     * map would grow unbounded over a 24/7 session. Wired by [TradingPipeline] to `mode == BACKTEST`.
     */
    private val trackRisk: Boolean = false,
    /** Raises an operator alert when a filled position cannot retain venue-side protection. */
    private val onProtectionFailure: (strategyId: String, message: String) -> Unit = { _, _ -> },
    /**
     * Returns a rejection reason when an engine-held request may no longer reach the broker.
     * Live wiring uses this to re-check halt state when a deferred entry materializes or fires.
     */
    private val engineHeldSubmissionBlockReason: (OrderRequest) -> String? = { null },
    /** Identifies requests that reduce current exposure and must survive a halt cancel sweep. */
    private val isRiskReducingForHalt: (OrderRequest) -> Boolean = { false },
    /**
     * Net strategy position for (strategyId, symbol), used to retire protective exits whose
     * position was consumed by an opposite entry (#1069). Null disables the sweep — venue
     * position semantics then depend entirely on the broker.
     */
    private val strategyNetQty: ((strategyId: String, symbol: String) -> BigDecimal)? = null,
    /**
     * How the venue accounts positions on a symbol. Read once per submitted request by
     * [LegIntentPlanner] and once per minted stack layer — never per tick or per fill.
     */
    private val positionMode: (symbol: String) -> PositionAccountingMode = { PositionAccountingMode.UNKNOWN },
    /**
     * Venue tickets the position ledger already holds for a strategy, read once at restore so
     * broker recovery joins those orders to their tickets without republishing executions the
     * book already reflects (#1096). Default: nothing booked.
     */
    private val bookedVenueTickets: (strategyId: String) -> Set<String> = { emptySet() },
) : PendingOrderExposureProvider {
    private val log = LoggerFactory.getLogger(OrderManager::class.java)

    private val orders: MutableMap<String, ManagedOrder> = mutableMapOf()
    private val exposureEntries: MutableMap<String, ExposureEntry> = mutableMapOf()
    private val exposureGroupScratch: MutableMap<String, BigDecimal> = mutableMapOf()

    private data class ExposureEntry(
        val request: OrderRequest,
        val groupId: String?,
        var filledQuantity: BigDecimal = BigDecimal.ZERO,
    )

    /**
     * Ids of orders that are not yet terminal. The per-tick [evaluateTriggers] scan walks this
     * instead of every order ever created, so its cost tracks live orders, not all-time orders.
     * A LinkedHashSet populated in [track] order keeps iteration order identical to [orders]
     * (a LinkedHashMap), so trigger-firing order is unchanged.
     */
    private val liveOrderIds: LinkedHashSet<String> = LinkedHashSet()

    /**
     * Live order ids bucketed by symbol, maintained in lockstep with [liveOrderIds]. Lets the
     * per-tick scan touch only the tick's symbol — O(this symbol's orders) instead of O(all live
     * orders) — so per-tick cost stays flat as more symbols/strategies are added.
     */
    private val liveBySymbol: MutableMap<String, LinkedHashSet<String>> = mutableMapOf()

    /**
     * Live orders carrying a GTD deadline, id -> deadline epoch ms, in [liveOrderIds] insertion
     * order. The per-tick expiry sweep (PaperBroker path) walks this instead of resolving every
     * live order each tick: most orders are GTC, so resolving the whole live set just to read an
     * absent deadline was the dominant cost of a bar-replay backtest. Insertion order matches
     * [liveOrderIds], so expired orders cancel in the same order as a full scan would.
     */
    private val gtdLive: LinkedHashMap<String, Long> = LinkedHashMap()

    /**
     * Ids awaiting reclamation. An order is enqueued when it goes terminal in [update]; [runGc]
     * drains it once per pass, reclaiming it if nothing references it and re-queuing it otherwise.
     */
    private val gcQueue: ArrayDeque<String> = ArrayDeque()

    private data class HaltCancelState(
        var attempts: Int,
        var nextAttemptAtMs: Long,
        var alerted: Boolean = false,
    )

    private val haltCancellations: MutableMap<String, HaltCancelState> = mutableMapOf()

    // Reusable per-tick scratch buffers for [evaluateTriggers]. Each is cleared and refilled every
    // tick; ArrayList.clear() retains capacity, so steady-state per-tick list allocation is zero.
    // Shareable only because evaluateTriggers runs on the single engine thread and is not reentrant
    // (its sole caller is the TickEvent subscription, and TickEvent is feed-sourced).
    private val symbolLiveScratch = ArrayList<ManagedOrder>()
    private val triggeredScratch = ArrayList<ManagedOrder>()
    private val expiredExitsScratch = ArrayList<OrderRequest.TimeExit>()
    private val gtdExpiredScratch = ArrayList<String>()
    private val expiredStacksScratch = ArrayList<StackTracker.ActiveStack>()

    private val trailingHwm: MutableMap<String, BigDecimal> = mutableMapOf()

    /**
     * One-way arming state for [OrderRequest.ArmedTrailingStop] orders. `false` while
     * the stop sits at `entry ± distance`; flips to `true` once MFE crosses the
     * threshold and the stop starts trailing [OrderRequest.ArmedTrailingStop.hwm].
     * Never reverts. See #48.
     */
    private val armedTrailArmed: MutableMap<String, Boolean> = mutableMapOf()
    private val steppedStopIndex: MutableMap<String, Int> = mutableMapOf()
    private val timeTightenIntervals: MutableMap<String, Long> = mutableMapOf()
    private val managedStopLevel: MutableMap<String, BigDecimal> = mutableMapOf()
    private var trailingStateDirty = false

    private val lastObservedPrice: MutableMap<String, BigDecimal> = mutableMapOf()

    private val siblings: MutableMap<String, List<String>> = mutableMapOf()

    /** Group id for each leg of an OCO that qkt, rather than the venue, must enforce. */
    private val emulatedOcoGroupByLeg: MutableMap<String, String> = mutableMapOf()
    private val engineHeldCloseTickets: MutableMap<String, String> = mutableMapOf()

    /**
     * Quantity the venue has closed against each attached-bracket entry, summed from
     * position-close observations, so a partial close does not complete the wrapper early.
     */
    private val venueClosedQuantityByEntry: MutableMap<String, BigDecimal> = mutableMapOf()

    private data class OcoCompensation(
        val strategyId: String,
        val positionTicket: String,
    )

    /** In-flight closes raised after both independently placed OCO legs executed. */
    private val ocoCompensations: MutableMap<String, OcoCompensation> = mutableMapOf()

    /**
     * In-flight sequencing for a [OrderRequest.StandaloneOCO] whose legs are placed one
     * acceptance at a time: leg2 is dispatched only after the venue accepts leg1, so a leg1
     * rejection can never leave a one-legged (directional) OCO. Indexed in [ocoByLeg1] /
     * [ocoByLeg2] under the id each leg's broker events arrive under — the entry id for a
     * bracket leg, the leg's own id otherwise (see [ocoFillId]).
     */
    private class OcoSequence(
        val ocoId: String,
        val leg1: OrderRequest,
        val leg1AckId: String,
        val leg2: OrderRequest,
        val leg2AckId: String,
    ) {
        var leg2Placed: Boolean = false
        var leg2Confirmed: Boolean = false

        /**
         * Set when leg1 fills before leg2's acceptance arrives: leg2's venue ticket isn't
         * known yet, so the sibling-cancel is deferred until [OrderManager.onAccepted] sees
         * leg2's acceptance and cancels it then.
         */
        var leg2PendingCancel: Boolean = false
    }

    /** OCO sequences awaiting leg1's acceptance, keyed by [OcoSequence.leg1AckId]. */
    private val ocoByLeg1: MutableMap<String, OcoSequence> = mutableMapOf()

    /** Active/in-flight OCO sequences keyed by [OcoSequence.leg2AckId], until the OCO resolves. */
    private val ocoByLeg2: MutableMap<String, OcoSequence> = mutableMapOf()

    private val pendingChildren: MutableMap<String, List<OrderRequest>> = mutableMapOf()

    /**
     * OTO wrappers whose parent is live and whose children are still unarmed, keyed by parent id.
     * Persistence snapshots scan only this bounded active set on order-state mutations; the tick
     * path never reads or scans it.
     */
    private val pendingOtosByParent: MutableMap<String, OrderRequest.OTO> = mutableMapOf()

    /** Original pre-fill brackets retained until their entry resolves, for durable re-arming. */
    private val preFillBrackets: MutableMap<String, OrderRequest.Bracket> = mutableMapOf()
    private val fillAnchoredFallbackBrackets: MutableMap<String, OrderRequest.Bracket> = mutableMapOf()
    private val fillAnchoredAttachedBrackets: MutableMap<String, OrderRequest.Bracket> = mutableMapOf()

    private sealed interface PendingPositionModification

    private data class StackPositionModification(
        val stackId: String,
        val layerOrderId: String,
        val fillPrice: BigDecimal,
        val stopLoss: BigDecimal?,
        val ticket: String,
        val strategyId: String,
    ) : PendingPositionModification

    private data class BracketPositionModification(
        val ticket: String,
        val strategyId: String,
        val fallbackStop: OrderRequest.Stop?,
    ) : PendingPositionModification

    private data class RatchetPositionModification(
        val orderId: String,
        val ticket: String,
        val strategyId: String,
        val stopLoss: BigDecimal,
    ) : PendingPositionModification

    private val pendingPositionModifications: MutableMap<String, PendingPositionModification> = mutableMapOf()
    private val persistedStrategies = mutableSetOf<String>()

    /**
     * Last snapshot written per (strategy, file). [persistAll] runs on every order state
     * change and walks every strategy that ever traded; without this, one fill re-fsyncs four
     * unchanged files for each of them, and the next pre-submit drain waits on all of it.
     */
    private val lastPersisted: MutableMap<Pair<String, String>, Any> = mutableMapOf()

    /** Pre-fill ScaleOut wrappers keyed by basis id so their activation survives restart. */
    private val pendingScaleOutsByBasis: MutableMap<String, OrderRequest.ScaleOut> = mutableMapOf()

    /** Owned position ticket reported by the latest partial execution of a ScaleOut basis. */
    private val partialScaleOutPositionTickets: MutableMap<String, String> = mutableMapOf()

    /** ScaleOut wrappers currently cascading an explicit user cancellation to their children. */
    private val cancellingScaleOutWrappers: MutableSet<String> = mutableSetOf()

    /** Filled ScaleOut wrappers retained while at least one ticketed exit remains live. */
    private val activeScaleOutsById: MutableMap<String, OrderRequest.ScaleOut> = mutableMapOf()
    private val scaleOutByExitId: MutableMap<String, String> = mutableMapOf()
    private val remainingScaleOutExitIds: MutableMap<String, MutableSet<String>> = mutableMapOf()

    /** Winning OCO legs whose first positive execution slice already started sibling cancellation. */
    private val ocoSiblingCancelStarted: MutableSet<String> = mutableSetOf()

    private val timeExits: MutableMap<String, OrderRequest.TimeExit> = mutableMapOf()

    private val stacks: StackTracker = StackTracker()

    private val riskByClientOrderId: MutableMap<String, BigDecimal> = java.util.concurrent.ConcurrentHashMap()
    private val protectionByClientOrderId: MutableMap<String, ProtectionLevels> =
        java.util.concurrent.ConcurrentHashMap()
    private val reportBracketByClientOrderId: MutableMap<String, OrderRequest.Bracket> = mutableMapOf()

    /** Exact protective stop and target prices resolved for an entry fill. */
    data class ProtectionLevels(
        val stopLoss: BigDecimal,
        val takeProfit: BigDecimal,
    )

    /** Dollar risk and protective prices resolved against one exact entry fill. */
    data class EntryRiskReport(
        val riskUsd: BigDecimal,
        val protection: ProtectionLevels,
    )

    /**
     * Returns and removes the recorded risk for [clientOrderId]. Designed to be called once per
     * fill — the entry is consumed so the map doesn't grow unbounded over a long-running session.
     */
    fun riskUsdFor(clientOrderId: String): BigDecimal? = riskByClientOrderId.remove(clientOrderId)

    /** Resolved venue prices attached to an entry fill; consumed once by the backtest report. */
    fun protectionFor(clientOrderId: String): ProtectionLevels? = protectionByClientOrderId.remove(clientOrderId)

    /**
     * Resolve and consume an entry bracket using the broker's actual [fillPrice] and [quantity].
     * The accounting subscriber runs before the ordinary order-state subscriber, so report
     * generation must not depend on [onFilled] having re-anchored relative children first.
     */
    fun entryRiskForFill(
        clientOrderId: String,
        quantity: BigDecimal,
        fillPrice: BigDecimal,
        symbol: String,
    ): EntryRiskReport? {
        if (!trackRisk) return null
        val bracket = reportBracketByClientOrderId[clientOrderId] ?: return null
        val ids = listOf(bracket.id, bracket.entry.id, clientOrderId).distinct()
        for (id in ids) {
            reportBracketByClientOrderId.remove(id)
            riskByClientOrderId.remove(id)
            protectionByClientOrderId.remove(id)
        }
        val resolved = resolveBracketAtFill(bracket, fillPrice)
        val stopPrice = stopPriceAtEntry(resolved, fillPrice)
        return EntryRiskReport(
            riskUsd = calculateRisk(quantity, fillPrice, stopPrice, symbol),
            protection = ProtectionLevels(stopPrice, resolved.takeProfit),
        )
    }

    private fun recordProtection(
        clientOrderIds: List<String>,
        stopLoss: BigDecimal,
        takeProfit: BigDecimal,
    ) {
        if (!trackRisk) return
        val protection = ProtectionLevels(stopLoss, takeProfit)
        for (id in clientOrderIds) protectionByClientOrderId[id] = protection
    }

    private fun recordRisk(
        clientOrderIds: List<String>,
        quantity: BigDecimal,
        entry: BigDecimal,
        stop: BigDecimal,
        symbol: String,
    ) {
        // Risk-per-trade is consumed only by the backtest report (via [riskUsdFor] in ReplayEngine).
        // In live nothing reads it, so recording would just leak ~2 entries per bracket forever.
        if (!trackRisk) return
        val risk = calculateRisk(quantity, entry, stop, symbol)
        for (id in clientOrderIds) riskByClientOrderId[id] = risk
    }

    private fun calculateRisk(
        quantity: BigDecimal,
        entry: BigDecimal,
        stop: BigDecimal,
        symbol: String,
    ): BigDecimal {
        val contractSize = instruments.lookup(symbol)?.contractSize ?: BigDecimal.ONE
        return entry
            .subtract(stop)
            .abs()
            .multiply(quantity, Money.CONTEXT)
            .multiply(contractSize, Money.CONTEXT)
    }

    private fun stopPriceAtEntry(
        bracket: OrderRequest.Bracket,
        entryPrice: BigDecimal,
    ): BigDecimal =
        when (val stop = bracket.stopLoss) {
            is StopLossSpec.Fixed -> stop.price
            is StopLossSpec.ArmedTrail ->
                if (bracket.side == Side.BUY) entryPrice - stop.trailDistance else entryPrice + stop.trailDistance
            is StopLossSpec.SteppedStop ->
                if (bracket.side == Side.BUY) entryPrice - stop.initialDistance else entryPrice + stop.initialDistance
            is StopLossSpec.TimeTighten ->
                if (bracket.side == Side.BUY) entryPrice - stop.initialDistance else entryPrice + stop.initialDistance
        }

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> onAccepted(e) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> onRejected(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> onFilled(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> onStackLayerFilled(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> evaluateStackFlat(e) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> onPartiallyFilled(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> onCancelled(e) }
        bus.subscribe<BrokerEvent.OrderCancelFailed> { e -> onCancelFailed(e) }
        bus.subscribe<BrokerEvent.PositionModificationCompleted> { e -> onPositionModificationCompleted(e) }
        bus.subscribe<TickEvent> { e -> evaluateTriggers(e.tick) }
    }

    fun submit(request: OrderRequest): SubmitAck =
        submitPlanned(LegIntentPlanner.plan(request, positionMode(request.symbol)))

    private fun submitPlanned(request: OrderRequest): SubmitAck {
        orders[request.id]?.takeIf { !it.state.isTerminal }?.let { existing ->
            return SubmitAck(
                clientOrderId = existing.id,
                brokerOrderId = existing.brokerOrderId,
                accepted = true,
            )
        }
        // Venue-faithful stops validation (#1076): MT5 rejects an order whose absolute stop
        // is already on the wrong side of the reference price (retcode 10016 Invalid stops).
        // Refusing locally keeps every simulated tier byte-consistent with live — on a gap
        // tick the entry is never taken, instead of filling with an INVERTED protective stop
        // that fires on the next print as a guaranteed instant loss. Market entries validate
        // against the current quote; pending entries against their own trigger price. Scope
        // is deliberately the stop side only: a take profit the market has already reached is
        // an instant profit-take, not broken protection, and BY-resolved targets are anchored
        // to the signal bar rather than the submit quote. Relative (BY/trail) stops resolve
        // off the fill and cannot invert.
        if (request is OrderRequest.Bracket) {
            val stopsReference =
                when (val entry = request.entry) {
                    is OrderRequest.Limit -> entry.limitPrice
                    is OrderRequest.Stop -> entry.stopPrice
                    else -> priceProvider.lastPrice(request.symbol)?.takeIf { it.signum() != 0 }
                }
            val fixedSl = (request.stopLoss as? StopLossSpec.Fixed)?.price
            if (stopsReference != null && fixedSl != null) {
                val slCrossed =
                    if (request.side == Side.BUY) fixedSl >= stopsReference else fixedSl <= stopsReference
                if (slCrossed) {
                    return rejectCrossedProtection(request, stopsReference, fixedSl, "stop loss")
                }
            }
            // The target needs the same check, but ONLY for an absolute `AT` level. A BY/PCT/RR
            // target is re-anchored off the fill by resolveBracketAtFill and cannot invert, and
            // its pre-fill value is a placeholder — checking that would reject healthy brackets.
            // An inverted absolute target is not a free profit-take: measured on the gold RSI-fade
            // tape, a BUY filled at 1320.700 carrying TAKE_PROFIT 1320.019 closed instantly for a
            // 0.68/oz LOSS. MT5 rejects it under the same retcode 10016 the stop side gets.
            if (stopsReference != null && request.takeProfitAst is com.qkt.dsl.ast.ChildAt) {
                val tp = request.takeProfit
                val tpCrossed =
                    if (request.side == Side.BUY) tp <= stopsReference else tp >= stopsReference
                if (tpCrossed) {
                    return rejectCrossedProtection(request, stopsReference, tp, "take profit")
                }
            }
        }
        val now = clock.now()
        track(
            ManagedOrder(
                id = request.id,
                request = request,
                state = OrderState.CREATED,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        if (!request.isCompositeShape()) registerExposure(request)
        return dispatch(request)
    }

    override fun quantityFor(
        symbol: String,
        side: Side,
        strategyId: String?,
    ): BigDecimal {
        var ungrouped = BigDecimal.ZERO
        exposureGroupScratch.clear()
        for ((id, entry) in exposureEntries) {
            val request = entry.request
            if (request.symbol != symbol || request.side != side) continue
            if (strategyId != null && request.strategyId != strategyId) continue
            if (orders[id]?.state?.isTerminal == true) continue
            val remaining = request.quantity.subtract(entry.filledQuantity).max(BigDecimal.ZERO)
            if (remaining.signum() == 0) continue
            val groupId = entry.groupId
            if (groupId == null) {
                ungrouped = ungrouped.add(remaining)
            } else {
                val prior = exposureGroupScratch[groupId]
                if (prior == null || remaining > prior) exposureGroupScratch[groupId] = remaining
            }
        }
        return exposureGroupScratch.values.fold(ungrouped, BigDecimal::add)
    }

    private fun registerExposure(
        request: OrderRequest,
        groupId: String? = null,
    ) {
        val existing = exposureEntries[request.id]
        exposureEntries[request.id] =
            ExposureEntry(
                request = request,
                groupId = existing?.groupId ?: groupId,
                filledQuantity = existing?.filledQuantity ?: BigDecimal.ZERO,
            )
    }

    private fun exposureEntryRequest(request: OrderRequest): OrderRequest =
        when (request) {
            is OrderRequest.Bracket -> request.entry.withStrategyId(request.strategyId)
            is OrderRequest.OTO -> request.parent
            is OrderRequest.ScaleOut -> request.basis
            is OrderRequest.TimeExit -> request.target
            else -> request
        }

    fun cancel(clientOrderId: String) {
        val managed = orders[clientOrderId] ?: return
        if (managed.state.isTerminal) return
        if (managed.request is OrderRequest.Stack) {
            stacks.get(clientOrderId)?.let { state ->
                for (pid in state.pendingLayerIds.toList()) cancel(pid)
            }
            stacks.terminate(clientOrderId)
            update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            exposureEntries.remove(clientOrderId)
            return
        }
        if (managed.childClientOrderIds.isNotEmpty()) {
            val scaleOutCancellation = managed.request is OrderRequest.ScaleOut
            if (scaleOutCancellation) cancellingScaleOutWrappers.add(clientOrderId)
            try {
                for (childId in managed.childClientOrderIds) cancel(childId)
                update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
                exposureEntries.remove(clientOrderId)
            } finally {
                if (scaleOutCancellation) cancellingScaleOutWrappers.remove(clientOrderId)
            }
            return
        }
        when (managed.state) {
            OrderState.CREATED, OrderState.PENDING -> {
                update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
                exposureEntries.remove(clientOrderId)
                completeScaleOutExit(clientOrderId, OrderState.CANCELLED)
            }
            else -> broker.cancel(clientOrderId)
        }
    }

    fun cancelPendingForSymbol(symbol: String) {
        // Cancel pending stacks targeting this symbol.
        val stackIds =
            stacks
                .all()
                .filter { state ->
                    val managed = orders[state.id] ?: return@filter false
                    (managed.request as? OrderRequest.Stack)?.symbol == symbol
                }.map { it.id }
        for (id in stackIds) cancel(id)
        // Cancel any remaining (non-stack) engine-held or venue-resting orders for the symbol
        // that aren't already children of a stack we just cancelled.
        val pending =
            orders.values
                .filter {
                    (it.state == OrderState.PENDING || it.state == OrderState.WORKING) &&
                        it.request.symbol == symbol
                }.map { it.id }
        for (id in pending) cancel(id)
    }

    /**
     * Cancel active entry intent after a risk halt, optionally limited to [strategyId].
     *
     * Protective monitors, risk-reducing exits, and composite containers whose entry has filled
     * remain active. Their risk-increasing pending children are still cancelled individually.
     */
    fun cancelEntriesForHalt(strategyId: String? = null) {
        val entryIds =
            orders.values
                .filter { managed ->
                    (managed.state == OrderState.PENDING || managed.state == OrderState.WORKING) &&
                        (strategyId == null || managed.request.strategyId == strategyId) &&
                        !mustSurviveHalt(managed)
                }.map { it.id }
        for (id in entryIds) {
            haltCancellations[id] = HaltCancelState(attempts = 1, nextAttemptAtMs = clock.now() + HALT_CANCEL_RETRY_MS)
            cancel(id)
        }
    }

    private fun mustSurviveHalt(managed: ManagedOrder): Boolean {
        if (managed.id in engineHeldCloseTickets) return true
        if (isPersistentManagedStop(managed.request)) return true
        if (isRiskReducingForHalt(managed.request)) return true
        return managed.childClientOrderIds.any { childId ->
            orders[childId]?.state == OrderState.FILLED
        }
    }

    /** Retry halt-owned cancellations that have not produced a terminal broker event. */
    fun retryHaltCancellations(nowMs: Long) {
        val due = haltCancellations.filterValues { nowMs >= it.nextAttemptAtMs }.keys.toList()
        for (id in due) {
            val managed = orders[id]
            if (managed == null || managed.state.isTerminal) {
                haltCancellations.remove(id)
                continue
            }
            val state = haltCancellations[id] ?: continue
            state.attempts += 1
            state.nextAttemptAtMs = nowMs + haltCancelDelayMs(state.attempts)
            if (state.attempts >= HALT_CANCEL_ALERT_ATTEMPTS && !state.alerted) {
                state.alerted = true
                reportProtectionFailure(
                    managed.request.strategyId,
                    "CRITICAL halt cancellation remains unconfirmed for ${managed.id} " +
                        "after ${state.attempts} attempts",
                )
            }
            broker.cancel(id)
        }
    }

    private fun onCancelFailed(event: BrokerEvent.OrderCancelFailed) {
        val state = haltCancellations[event.clientOrderId] ?: return
        state.nextAttemptAtMs = minOf(state.nextAttemptAtMs, clock.now() + HALT_CANCEL_RETRY_MS)
    }

    private fun haltCancelDelayMs(attempts: Int): Long =
        (HALT_CANCEL_RETRY_MS * (1L shl (attempts - 1).coerceAtMost(5))).coerceAtMost(HALT_CANCEL_MAX_RETRY_MS)

    fun getOrder(clientOrderId: String): ManagedOrder? = orders[clientOrderId]

    /** Sibling order ids linked to [clientOrderId] — exposed for restart-recovery tests. */
    fun siblingsOf(clientOrderId: String): List<String> = siblings[clientOrderId].orEmpty()

    /**
     * Rebuild pending order tracking and sibling linkage from the persistor for [strategyIds].
     * Venue-held orders are handed to the broker for reconciliation; engine-held orders resume as
     * [OrderState.PENDING] monitors and are deliberately excluded from venue recovery. Called once
     * at session startup. Persistence read failures abort startup rather than silently discarding
     * live order state.
     */
    fun restore(strategyIds: List<String>) {
        val recovered = mutableListOf<ManagedOrder>()
        for (sid in strategyIds) {
            persistedStrategies.add(sid)
            val dynamicStops =
                persistor
                    .loadTrailingStops(sid)
                    .associateBy { it.clientOrderId }
                    .toMutableMap()
            for (leg in persistor.loadOcoLegs(sid)) {
                if (orders.containsKey(leg.clientOrderId)) continue
                val groupId =
                    (leg.siblingIds + leg.clientOrderId)
                        .sorted()
                        .joinToString(prefix = "restored-oco:", separator = "|")
                emulatedOcoGroupByLeg[leg.clientOrderId] = groupId
                if (isEngineHeldOnRestore(leg.request)) {
                    siblings[leg.clientOrderId] = leg.siblingIds
                    val persisted = dynamicStops.remove(leg.clientOrderId)
                    if (persisted == null && hasPersistentDynamicState(leg.request)) {
                        log.warn(
                            "[restore] dynamic state missing for {}; restarting from its available anchor",
                            leg.clientOrderId,
                        )
                    }
                    restoreEngineHeldOrder(
                        clientOrderId = leg.clientOrderId,
                        brokerOrderId = leg.brokerOrderId,
                        request = leg.request,
                        dynamicState = persisted,
                        groupId = groupId,
                    )
                    continue
                }
                val now = clock.now()
                val managed =
                    ManagedOrder(
                        id = leg.clientOrderId,
                        request = leg.request,
                        state = OrderState.WORKING,
                        brokerOrderId = leg.brokerOrderId,
                        createdAt = now,
                        lastUpdatedAt = now,
                    )
                orders[leg.clientOrderId] = managed
                indexLive(managed)
                siblings[leg.clientOrderId] = leg.siblingIds
                registerExposure(leg.request, groupId)
                recovered += managed
            }
            val pairs = persistor.loadBracketPairs(sid)
            for (pair in pairs) {
                val exitIds = listOfNotNull(pair.stopLossClientOrderId, pair.takeProfitClientOrderId)
                for (exitId in exitIds) {
                    siblings[exitId] = exitIds.filter { it != exitId }
                }
            }
            val pendingOrders = persistor.loadPendingOrders(sid)
            for ((id, request) in pendingOrders) {
                if (request is OrderRequest.ScaleOut && id == request.id) {
                    restoreActiveScaleOut(request, pendingOrders.keys)
                }
            }
            for ((id, request) in pendingOrders) {
                if (orders.containsKey(id)) continue
                if (request is OrderRequest.OTO) {
                    require(id == request.parent.id) {
                        "persisted OTO ${request.id} keyed by $id instead of parent ${request.parent.id}"
                    }
                    restorePendingOto(request, recovered)
                    continue
                }
                if (request is OrderRequest.ScaleOut) {
                    if (id == request.id) continue
                    require(id == request.basis.id) {
                        "persisted ScaleOut ${request.id} keyed by $id instead of basis ${request.basis.id}"
                    }
                    restorePendingScaleOut(request, recovered)
                    continue
                }
                if (request is OrderRequest.Bracket) {
                    restorePendingBracket(request, recovered)
                    continue
                }
                if (isEngineHeldOnRestore(request)) {
                    restoreEngineHeldOrder(
                        clientOrderId = id,
                        brokerOrderId = null,
                        request = request,
                        dynamicState = dynamicStops.remove(id),
                        groupId = null,
                    )
                    continue
                }
                val now = clock.now()
                val engineHeldScaleOutExit =
                    request is OrderRequest.IfTouched && request.closesTicket != null
                val managed =
                    ManagedOrder(
                        id = id,
                        request = request,
                        state = if (engineHeldScaleOutExit) OrderState.PENDING else OrderState.WORKING,
                        parentClientOrderId = scaleOutByExitId[id],
                        createdAt = now,
                        lastUpdatedAt = now,
                    )
                orders[id] = managed
                indexLive(managed)
                registerExposure(request)
                if (!engineHeldScaleOutExit) recovered += managed
            }
            // Older journals may contain a dynamic stop without the duplicate pending-order
            // snapshot. Keep accepting that shape after the current OCO and pending snapshots have
            // consumed their matching state.
            for (stop in dynamicStops.values) {
                restoreEngineHeldOrder(
                    clientOrderId = stop.clientOrderId,
                    brokerOrderId = stop.brokerOrderId,
                    request = stop.request,
                    dynamicState = stop,
                    groupId = null,
                )
            }
        }
        if (recovered.isNotEmpty()) {
            val booked = strategyIds.flatMapTo(LinkedHashSet()) { bookedVenueTickets(it) }
            val accounted = broker.recoverPendingOrders(recovered, booked)
            // A restored working order the venue cannot account for — no pending ticket, no
            // position, nothing to track — is a phantom: pre-#1048 attached-bracket wrappers
            // whose position closed long ago. Left alone it holds exposure for the whole
            // session and never reaches a terminal state. Retire it through the ordinary cancel
            // path so exposure, children and persistence unwind exactly as a venue cancel would.
            val vanished = recovered.filter { it.id !in accounted }
            for (order in vanished) {
                log.warn(
                    "[restore] {} {} {} has no venue counterpart after recovery; retiring stale order",
                    order.request.strategyId,
                    order.id,
                    order.request::class.simpleName,
                )
                onCancelled(
                    BrokerEvent.OrderCancelled(
                        clientOrderId = order.id,
                        brokerOrderId = null,
                        reason = "not at venue after recovery",
                        strategyId = order.request.strategyId,
                        timestamp = clock.now(),
                    ),
                )
            }
            if (vanished.isNotEmpty()) {
                log.warn("[restore] retired {} stale order(s) with no venue counterpart", vanished.size)
            }
        }
    }

    private fun restorePendingScaleOut(
        request: OrderRequest.ScaleOut,
        recovered: MutableList<ManagedOrder>,
    ) {
        require(request.basis.id != request.id) { "ScaleOut ${request.id} basis must have a distinct id" }
        require(orders[request.id] == null) {
            "persisted ScaleOut ${request.id} collides with already-restored order state"
        }
        val now = clock.now()
        val wrapper =
            ManagedOrder(
                id = request.id,
                request = request,
                state = OrderState.WORKING,
                childClientOrderIds = listOf(request.basis.id),
                createdAt = now,
                lastUpdatedAt = now,
            )
        val basis =
            ManagedOrder(
                id = request.basis.id,
                request = request.basis,
                state = OrderState.WORKING,
                parentClientOrderId = request.id,
                createdAt = now,
                lastUpdatedAt = now,
            )
        orders[wrapper.id] = wrapper
        indexLive(wrapper)
        orders[basis.id] = basis
        indexLive(basis)
        pendingScaleOutsByBasis[basis.id] = request
        registerExposure(exposureEntryRequest(request.basis))
        recovered += basis
    }

    private fun restoreActiveScaleOut(
        request: OrderRequest.ScaleOut,
        persistedIds: Set<String>,
    ) {
        val exitIds =
            request.legs.indices
                .map { "${request.id}-leg-$it" }
                .filterTo(linkedSetOf()) { it in persistedIds }
        if (exitIds.isEmpty()) return
        require(orders[request.id] == null) {
            "persisted active ScaleOut ${request.id} collides with already-restored order state"
        }
        val now = clock.now()
        val wrapper =
            ManagedOrder(
                id = request.id,
                request = request,
                state = OrderState.WORKING,
                childClientOrderIds = listOf(request.basis.id) + request.legs.indices.map { "${request.id}-leg-$it" },
                createdAt = now,
                lastUpdatedAt = now,
            )
        orders[wrapper.id] = wrapper
        indexLive(wrapper)
        activeScaleOutsById[request.id] = request
        remainingScaleOutExitIds[request.id] = exitIds
        for (exitId in exitIds) scaleOutByExitId[exitId] = request.id
    }

    private fun restorePendingOto(
        request: OrderRequest.OTO,
        recovered: MutableList<ManagedOrder>,
    ) {
        require(request.parent.id != request.id) { "OTO ${request.id} parent must have a distinct id" }
        require(request.children.none { it.id == request.id || it.id == request.parent.id }) {
            "OTO ${request.id} child ids must differ from the wrapper and parent ids"
        }
        require(
            request.children
                .map { it.id }
                .distinct()
                .size == request.children.size,
        ) {
            "OTO ${request.id} child ids must be unique"
        }
        require(orders[request.id] == null && request.children.none { orders[it.id] != null }) {
            "persisted OTO ${request.id} collides with already-restored order state"
        }
        val now = clock.now()
        val childIds = request.children.map { it.id }
        val wrapper =
            ManagedOrder(
                id = request.id,
                request = request,
                state = OrderState.WORKING,
                childClientOrderIds = listOf(request.parent.id) + childIds,
                createdAt = now,
                lastUpdatedAt = now,
            )
        orders[wrapper.id] = wrapper
        indexLive(wrapper)

        val parent =
            ManagedOrder(
                id = request.parent.id,
                request = request.parent,
                state = OrderState.WORKING,
                parentClientOrderId = request.id,
                createdAt = now,
                lastUpdatedAt = now,
            )
        orders[parent.id] = parent
        indexLive(parent)
        for (child in request.children) {
            val managed =
                ManagedOrder(
                    id = child.id,
                    request = child,
                    state = OrderState.CREATED,
                    parentClientOrderId = request.id,
                    createdAt = now,
                    lastUpdatedAt = now,
                )
            orders[managed.id] = managed
            indexLive(managed)
        }
        pendingChildren[parent.id] = request.children
        pendingOtosByParent[parent.id] = request
        registerExposure(exposureEntryRequest(request.parent))
        recovered += parent
    }

    private fun restorePendingBracket(
        request: OrderRequest.Bracket,
        recovered: MutableList<ManagedOrder>,
    ) {
        val caps = broker.capabilitiesFor(request.symbol)
        val isEngineManagedStop = request.stopLoss !is StopLossSpec.Fixed
        val needsFillAnchor =
            (request.stopLossAst != null && request.stopLossAst !is com.qkt.dsl.ast.ChildAt) ||
                (request.takeProfitAst != null && request.takeProfitAst !is com.qkt.dsl.ast.ChildAt)
        val canAttach =
            OrderTypeCapability.BRACKET in caps && OrderTypeCapability.POSITION_MODIFY in caps
        val now = clock.now()

        when {
            canAttach -> {
                val attached = request.copy(id = request.entry.id)
                val managed =
                    ManagedOrder(
                        id = attached.id,
                        request = attached,
                        state = OrderState.WORKING,
                        createdAt = now,
                        lastUpdatedAt = now,
                    )
                orders[attached.id] = managed
                indexLive(managed)
                preFillBrackets[attached.id] = request
                if (needsFillAnchor) fillAnchoredAttachedBrackets[attached.id] = request
                buildAttachedManagedStop(request, now)?.let { stop ->
                    track(
                        ManagedOrder(
                            id = stop.id,
                            request = stop,
                            state = OrderState.CREATED,
                            parentClientOrderId = request.id,
                            createdAt = now,
                            lastUpdatedAt = now,
                        ),
                    )
                    pendingChildren[attached.id] = listOf(stop)
                }
                registerExposure(attached)
                recovered += managed
            }
            !isEngineManagedStop && !needsFillAnchor && OrderTypeCapability.BRACKET in caps -> {
                val managed =
                    ManagedOrder(
                        id = request.id,
                        request = request,
                        state = OrderState.WORKING,
                        createdAt = now,
                        lastUpdatedAt = now,
                    )
                orders[request.id] = managed
                indexLive(managed)
                registerExposure(request)
                recovered += managed
            }
            else -> {
                val entry = request.entry.withStrategyId(request.strategyId)
                val managed =
                    ManagedOrder(
                        id = entry.id,
                        request = entry,
                        state = OrderState.WORKING,
                        createdAt = now,
                        lastUpdatedAt = now,
                    )
                orders[entry.id] = managed
                indexLive(managed)
                preFillBrackets[entry.id] = request
                if (needsFillAnchor) {
                    fillAnchoredFallbackBrackets[entry.id] = request
                } else {
                    pendingChildren[entry.id] =
                        listOf(
                            bracketExitOco(
                                request,
                                bracketEntryEstimate(request),
                                request.quantity,
                            ),
                        )
                }
                registerExposure(entry)
                recovered += managed
            }
        }
    }

    private fun restoreEngineHeldOrder(
        clientOrderId: String,
        brokerOrderId: String?,
        request: OrderRequest,
        dynamicState: com.qkt.persistence.PersistedTrailingStop?,
        groupId: String?,
    ) {
        if (orders.containsKey(clientOrderId)) return
        require(request.id == clientOrderId) {
            "persisted engine-held order $clientOrderId contains request ${request.id}"
        }
        require(dynamicState == null || dynamicState.clientOrderId == clientOrderId) {
            "dynamic state ${dynamicState?.clientOrderId} does not belong to $clientOrderId"
        }
        val now = clock.now()
        val managed =
            ManagedOrder(
                id = clientOrderId,
                request = request,
                state = OrderState.PENDING,
                brokerOrderId = brokerOrderId,
                createdAt = now,
                lastUpdatedAt = now,
            )
        orders[clientOrderId] = managed
        indexLive(managed)
        dynamicState?.let { trailingHwm[clientOrderId] = it.hwm }
        when (request) {
            is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit -> Unit
            is OrderRequest.ArmedTrailingStop ->
                armedTrailArmed[clientOrderId] = dynamicState?.armed ?: false
            is OrderRequest.SteppedStop -> {
                steppedStopIndex[clientOrderId] = dynamicState?.stepIndex ?: 0
                managedStopLevel[clientOrderId] =
                    dynamicState?.stopLevel
                        ?: initialStopLevel(request.side, request.entryPrice, request.initialDistance)
            }
            is OrderRequest.TimeTighteningStop -> {
                timeTightenIntervals[clientOrderId] = dynamicState?.elapsedIntervals ?: 0L
                managedStopLevel[clientOrderId] =
                    dynamicState?.stopLevel
                        ?: initialStopLevel(request.side, request.entryPrice, request.initialDistance)
            }
            is OrderRequest.StopLimit -> Unit
            else -> error("unsupported restored engine-held order ${request::class.simpleName}")
        }
        when (request) {
            is OrderRequest.ArmedTrailingStop -> trailingHwm.putIfAbsent(clientOrderId, request.entryPrice)
            is OrderRequest.SteppedStop -> trailingHwm.putIfAbsent(clientOrderId, request.entryPrice)
            else -> Unit
        }
        registerExposure(request, groupId)
    }

    /** Symbol, side, and quantity submitted under [clientOrderId]. */
    data class OrderDetails(
        val symbol: String,
        val side: Side,
        val quantity: BigDecimal,
    )

    /**
     * Recover the originating symbol/side/quantity for [clientOrderId] — the fields a
     * [BrokerEvent.OrderRejected] event omits. Returns `null` for an order this manager
     * never saw. A rejected order is retained only until the next GC drain (a tick), so read
     * this synchronously within the rejection handler; a deferred read may find it reclaimed.
     */
    fun orderDetailsFor(clientOrderId: String): OrderDetails? =
        orders[clientOrderId]?.request?.let { OrderDetails(it.symbol, it.side, it.quantity) }

    fun activeOrders(): List<ManagedOrder> = orders.values.filter { !it.state.isTerminal }

    /**
     * Count active, risk-increasing entry orders for [strategyId] on [symbol].
     *
     * The count uses the existing per-symbol live index and exposure registry, so its hot-path
     * cost is O(active orders for this symbol), not O(all orders). Dormant composite children and
     * protective or otherwise risk-reducing exits are excluded. Submitted and partially-filled
     * entries count as active to cover the acknowledgement and residual-fill lifecycle windows.
     */
    fun activeEntryOrderCount(
        strategyId: String,
        symbol: String,
    ): Int {
        val ids = liveBySymbol[symbol] ?: return 0
        var count = 0
        for (id in ids) {
            if (id !in exposureEntries) continue
            val managed = orders[id] ?: error("live order index desync: $id")
            if (managed.request.strategyId != strategyId) continue
            val activeEntry =
                when (managed.state) {
                    OrderState.PENDING,
                    OrderState.SUBMITTED,
                    OrderState.WORKING,
                    OrderState.PARTIALLY_FILLED,
                    -> true
                    else -> false
                }
            if (activeEntry && !mustSurviveHalt(managed)) count++
        }
        return count
    }

    /**
     * Read-only: true iff a live order on [symbol] could fill within the bar range `[low, high]`.
     * Direction-aware, so a gap-open through a level still counts (a buy stop at 100 fires on a bar
     * that opens at 102). A live trailing stop always returns true — its level moves with the
     * intrabar path, so the bar extremes alone cannot rule a fill out. Backs the tick-resolved fill
     * replay's decision to decode a bar's ticks; never mutates state or fires a trigger. e.g. a
     * resting buy stop at 100 with a bar `[98, 101]` -> true; with `[96, 99]` -> false.
     */
    fun intrabarFill(
        symbol: String,
        low: BigDecimal,
        high: BigDecimal,
        maxHalfSpread: BigDecimal = BigDecimal.ZERO,
    ): IntrabarFill {
        // Time-based exits (GTD expiry, TimeExit, stack deadline) fire on time, not price, so a
        // fill/cancel can land on a tick the new-extreme filter would skip. Conservatively bail to a
        // full real-tick replay whenever any is live.
        if (gtdLive.isNotEmpty() ||
            timeExits.isNotEmpty() ||
            stacks.activeView().any { it.deadlineEpochMs != null }
        ) {
            return IntrabarFill.ALL_TICKS
        }
        val ids = liveBySymbol[symbol] ?: return IntrabarFill.SYNTHETIC
        // Candles aggregate mid prices, while venue triggers use ask for BUY and bid for SELL.
        // Expand the mid range by the largest observed half-spread so a level crossed only by
        // the executable quote still selects real-tick resolution.
        val executableLow = low - maxHalfSpread
        val executableHigh = high + maxHalfSpread
        var fillable = false
        for (id in ids) {
            val m = orders[id] ?: continue
            if (m.state.isTerminal) continue
            when (val r = m.request) {
                is OrderRequest.Stop ->
                    if (stopReached(r.side, executableLow, executableHigh, r.stopPrice)) fillable = true
                is OrderRequest.StopLimit ->
                    if (stopReached(r.side, executableLow, executableHigh, r.stopPrice)) fillable = true
                is OrderRequest.Limit ->
                    if (limitReached(r.side, executableLow, executableHigh, r.limitPrice)) fillable = true
                is OrderRequest.IfTouched ->
                    if (limitReached(r.side, executableLow, executableHigh, r.triggerPrice)) fillable = true
                // Trailing/composite shapes (OTO, OCO, trailing stops, ...) move with the path; their
                // trigger is not a fixed level we can search for, so resolve the bar on real ticks.
                else -> return IntrabarFill.ALL_TICKS
            }
        }
        return if (fillable) IntrabarFill.EXTREMES else IntrabarFill.SYNTHETIC
    }

    // A stop / stop-limit trigger needs the bar to reach UP to the level for a buy (high >= level),
    // or DOWN for a sell (low <= level). Direction-aware, so a gap-open through the level counts.
    private fun stopReached(
        side: Side,
        low: BigDecimal,
        high: BigDecimal,
        level: BigDecimal,
    ): Boolean = if (side == Side.BUY) high >= level else low <= level

    // A limit / if-touched fill needs a dip DOWN to the level for a buy (low <= level), or a rise UP
    // for a sell (high >= level).
    private fun limitReached(
        side: Side,
        low: BigDecimal,
        high: BigDecimal,
        level: BigDecimal,
    ): Boolean = if (side == Side.BUY) low <= level else high >= level

    fun pendingOrders(): List<ManagedOrder> = orders.values.filter { it.state == OrderState.PENDING }

    private fun dispatch(request: OrderRequest): SubmitAck =
        when (request) {
            is OrderRequest.Market, is OrderRequest.Limit -> submitToBroker(request)

            is OrderRequest.Stop ->
                if (OrderTypeCapability.STOP in broker.capabilitiesFor(request.symbol)) {
                    submitToBroker(request)
                } else {
                    holdPending(request)
                }

            is OrderRequest.StopLimit ->
                if (OrderTypeCapability.STOP_LIMIT in broker.capabilitiesFor(request.symbol)) {
                    submitToBroker(request)
                } else {
                    holdPending(request)
                }

            is OrderRequest.IfTouched ->
                if (request.closesTicket == null &&
                    OrderTypeCapability.IF_TOUCHED in broker.capabilitiesFor(request.symbol)
                ) {
                    submitToBroker(request)
                } else {
                    holdPending(request)
                }

            is OrderRequest.TrailingStop,
            is OrderRequest.TrailingStopLimit,
            is OrderRequest.ArmedTrailingStop,
            is OrderRequest.SteppedStop,
            is OrderRequest.TimeTighteningStop,
            -> holdPending(request)

            is OrderRequest.StandaloneOCO ->
                if (OrderTypeCapability.OCO in broker.capabilitiesFor(request.symbol)) {
                    submitRegisteredToBroker(request)
                } else {
                    submitOco(request)
                }

            is OrderRequest.OTO -> submitOto(request)

            is OrderRequest.Bracket -> {
                val entryEstimate = priceProvider.lastPrice(request.symbol) ?: BigDecimal.ZERO
                if (entryEstimate.signum() != 0) {
                    val riskStop =
                        when (val sl = request.stopLoss) {
                            is StopLossSpec.Fixed -> sl.price
                            is StopLossSpec.ArmedTrail ->
                                // Pre-arm stop level is `entry ± trailDistance`; risk recording
                                // sees the worst-case loss the bracket can take.
                                if (request.side == Side.BUY) {
                                    entryEstimate - sl.trailDistance
                                } else {
                                    entryEstimate + sl.trailDistance
                                }
                            is StopLossSpec.SteppedStop ->
                                if (request.side == Side.BUY) {
                                    entryEstimate - sl.initialDistance
                                } else {
                                    entryEstimate + sl.initialDistance
                                }
                            is StopLossSpec.TimeTighten ->
                                if (request.side == Side.BUY) {
                                    entryEstimate - sl.initialDistance
                                } else {
                                    entryEstimate + sl.initialDistance
                                }
                        }
                    recordRisk(
                        clientOrderIds = listOf(request.id, request.entry.id),
                        quantity = request.quantity,
                        entry = entryEstimate,
                        stop = riskStop,
                        symbol = request.symbol,
                    )
                    recordProtection(
                        clientOrderIds = listOf(request.id, request.entry.id),
                        stopLoss = riskStop,
                        takeProfit = request.takeProfit,
                    )
                }
                if (trackRisk) {
                    reportBracketByClientOrderId[request.id] = request
                    reportBracketByClientOrderId[request.entry.id] = request
                }
                val caps = broker.capabilitiesFor(request.symbol)
                val isEngineManagedStop =
                    request.stopLoss is StopLossSpec.ArmedTrail ||
                        request.stopLoss is StopLossSpec.SteppedStop ||
                        request.stopLoss is StopLossSpec.TimeTighten
                val needsFillAnchor =
                    (request.stopLossAst != null && request.stopLossAst !is com.qkt.dsl.ast.ChildAt) ||
                        (request.takeProfitAst != null && request.takeProfitAst !is com.qkt.dsl.ast.ChildAt)
                val canAttach =
                    OrderTypeCapability.BRACKET in caps && OrderTypeCapability.POSITION_MODIFY in caps
                when {
                    // Venue that both attaches SL/TP to an order and can modify an open position's
                    // SL/TP: ship the bracket keyed under its entry id so the venue holds the SL/TP
                    // on the position (closing that ticket on a hedging account instead of a resting
                    // exit opening a counter) and the fill flows through the entry.id tracking paths.
                    // Armed trail also runs the engine trail on top (fires close-by-ticket at the
                    // tightened level, #278); the venue's attached stop is the offline backstop.
                    canAttach -> submitBracketAttached(request)
                    // BRACKET but no position-modify, fixed SL: ship whole (venue attaches SL/TP,
                    // nothing to trail).
                    !isEngineManagedStop && !needsFillAnchor && OrderTypeCapability.BRACKET in caps ->
                        submitRegisteredToBroker(request)
                    // No venue attach (backtest / restricted venue): decompose into engine-watched
                    // resting exits.
                    else -> submitBracketFallback(request)
                }
            }

            is OrderRequest.ScaleOut -> submitScaleOut(request)

            is OrderRequest.TimeExit -> submitTimeExit(request)

            is OrderRequest.Stack -> submitStack(request)

            else -> error("Order type ${request::class.simpleName} dispatch not yet implemented (added later in 7d-b)")
        }

    private fun submitScaleOut(req: OrderRequest.ScaleOut): SubmitAck {
        val strategyId = req.strategyId.ifBlank { req.basis.strategyId }
        val normalized = req.copy(strategyId = strategyId, basis = req.basis.withStrategyId(strategyId))
        val now = clock.now()
        update(req.id) {
            it.copy(
                request = normalized,
                state = OrderState.WORKING,
                childClientOrderIds = listOf(normalized.basis.id),
                lastUpdatedAt = now,
            )
        }
        track(
            ManagedOrder(
                id = normalized.basis.id,
                request = normalized.basis,
                state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        pendingScaleOutsByBasis[normalized.basis.id] = normalized
        registerExposure(exposureEntryRequest(normalized.basis))
        dispatch(normalized.basis)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    private fun submitTimeExit(req: OrderRequest.TimeExit): SubmitAck {
        val now = clock.now()
        update(req.id) {
            it.copy(
                state = OrderState.WORKING,
                childClientOrderIds = listOf(req.target.id),
                lastUpdatedAt = now,
            )
        }
        track(
            ManagedOrder(
                id = req.target.id,
                request = req.target,
                state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        timeExits[req.id] = req
        registerExposure(exposureEntryRequest(req.target))
        dispatch(req.target)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    private fun submitStack(req: OrderRequest.Stack): SubmitAck {
        val firstLayer =
            req.plan.layers.firstOrNull()
                ?: error("StackPlan must have at least one layer")
        // Layer 1 may be Immediate (market) or At (pending limit/stop). Both are supported.
        stacks.register(req.id, req.plan, req.plan.outerBracket)
        val now = clock.now()
        val firstOrderId = "${req.id}-l1"
        stacks.setLayerOneOrderId(req.id, firstOrderId)
        val firstQty = resolveLayerQuantity(firstLayer)
        val firstTriggerPrice: BigDecimal? =
            when (val t = firstLayer.trigger) {
                Immediate -> null
                is At -> {
                    require(!referencesStackEntryRef(t.price)) {
                        "STACK layer 1 AT expression cannot reference 'entry' (anchor is set by layer 1's fill)"
                    }
                    evaluateAt(t.price, anchor = BigDecimal.ZERO)
                }
            }
        val firstReq = buildLayerOrder(firstOrderId, req, firstLayer, firstQty, triggerPrice = firstTriggerPrice)
        track(
            ManagedOrder(
                id = firstOrderId,
                request = firstReq,
                state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        update(req.id) {
            it.copy(
                state = OrderState.WORKING,
                childClientOrderIds = listOf(firstOrderId),
                lastUpdatedAt = now,
            )
        }
        registerExposure(firstReq)
        dispatch(firstReq)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    private fun onStackLayerFilled(e: BrokerEvent.OrderFilled) {
        val filledQuantity = orders[e.clientOrderId]?.cumulativeFilledQuantity ?: e.quantity
        val owner = stacks.markFilled(e.clientOrderId, filledQuantity) ?: return
        val state = stacks.get(owner) ?: return
        // Anchor capture happens only on layer 1.
        if (state.layerOneOrderId == e.clientOrderId && state.anchor == null) {
            stacks.setAnchor(owner, e.price, clock.now())
            materializePendingLayers(owner, anchor = e.price)
        }
        // On a venue that holds attached position SL/TP, attach the layer's fixed exits to the
        // position so the broker closes that exact ticket — a resting exit order would instead
        // open a counter on a hedging account. Otherwise decompose into separate resting exits.
        if (OrderTypeCapability.POSITION_MODIFY in broker.capabilitiesFor(e.symbol)) {
            attachLayerSlTpToVenue(
                stackId = owner,
                layerOrderId = e.clientOrderId,
                fillPrice = e.price,
                ticket = e.brokerOrderId,
                operationId = "stack:${e.clientOrderId}:${e.sequenceId}",
            )
            return
        }
        val slId = "${e.clientOrderId}-sl"
        val tpId = "${e.clientOrderId}-tp"
        val slDistance = attachLayerSl(stackId = owner, layerOrderId = e.clientOrderId, fillPrice = e.price)
        val hadTp =
            attachLayerTp(stackId = owner, layerOrderId = e.clientOrderId, fillPrice = e.price, slDistance = slDistance)
        if (slDistance != null && hadTp) {
            siblings[slId] = listOf(tpId)
            siblings[tpId] = listOf(slId)
        }
    }

    /**
     * Attach a filled stack layer's fixed SL/TP to its venue position, so the broker closes that
     * exact ticket when a level is hit. The levels are computed off the actual fill (a stack fires
     * at market, so they aren't known until fill) — hence a position modify rather than the entry
     * wire. Used when the broker supports [OrderTypeCapability.POSITION_MODIFY]; without it the
     * layer's exits rest as separate orders (see [attachLayerSl] / [attachLayerTp]).
     */
    private fun attachLayerSlTpToVenue(
        stackId: String,
        layerOrderId: String,
        fillPrice: BigDecimal,
        ticket: String?,
        operationId: String,
    ) {
        val state = stacks.get(stackId) ?: return
        val parent = (orders[stackId]?.request as? OrderRequest.Stack) ?: return
        val resolvedTicket =
            ticket?.takeIf { it.isNotBlank() }
                ?: closeTicketFor?.invoke(parent.strategyId, layerOrderId)
        if (resolvedTicket == null) {
            reportProtectionFailure(
                parent.strategyId,
                "filled stack layer $layerOrderId has no venue ticket; SL/TP cannot be attached",
            )
            return
        }
        val slPrice =
            state.outerBracket?.stopLoss?.let {
                computeChildPrice(it, parent.side, fillPrice, isStopLoss = true)
            }
        val slDistance = slPrice?.let { (fillPrice - it).abs() }
        val tpPrice =
            state.outerBracket?.takeProfit?.let {
                computeChildPrice(it, parent.side, fillPrice, isStopLoss = false, slDistance = slDistance)
            }
        if (slPrice == null && tpPrice == null) return
        pendingPositionModifications[operationId] =
            StackPositionModification(
                stackId = stackId,
                layerOrderId = layerOrderId,
                fillPrice = fillPrice,
                stopLoss = slPrice,
                ticket = resolvedTicket,
                strategyId = parent.strategyId,
            )
        modifyPositionAsync(operationId, resolvedTicket, slPrice, tpPrice)
    }

    private fun modifyPositionAsync(
        operationId: String,
        ticket: String,
        sl: BigDecimal?,
        tp: BigDecimal?,
    ) {
        runCatching {
            broker.modifyPositionAsync(ticket, sl, tp) { ack ->
                bus.publish(
                    BrokerEvent.PositionModificationCompleted(
                        operationId = operationId,
                        ticket = ticket,
                        accepted = ack.accepted,
                        rejectReason = ack.rejectReason,
                    ),
                )
            }
        }.onFailure { error ->
            bus.publish(
                BrokerEvent.PositionModificationCompleted(
                    operationId = operationId,
                    ticket = ticket,
                    accepted = false,
                    rejectReason = error.message,
                ),
            )
        }
    }

    private fun onPositionModificationCompleted(event: BrokerEvent.PositionModificationCompleted) {
        val pending = pendingPositionModifications.remove(event.operationId) ?: return
        if (event.accepted) return
        when (pending) {
            is StackPositionModification -> {
                val fallbackStop =
                    attachLayerSl(
                        stackId = pending.stackId,
                        layerOrderId = pending.layerOrderId,
                        fillPrice = pending.fillPrice,
                        engineHeldCloseTicket = pending.ticket,
                    )
                reportProtectionFailure(
                    pending.strategyId,
                    "venue rejected attached SL/TP for ticket ${pending.ticket}: ${event.rejectReason}; " +
                        if (fallbackStop != null && pending.stopLoss != null) {
                            "engine-held stop armed at ${pending.stopLoss.toPlainString()}"
                        } else {
                            "no stop-loss was configured for fallback"
                        },
                )
            }
            is BracketPositionModification -> {
                pending.fallbackStop?.let { armFillAnchoredFallbackStop(it, pending.ticket) }
                reportProtectionFailure(
                    pending.strategyId,
                    "venue rejected fill-anchored bracket modify for ticket ${pending.ticket}: " +
                        "${event.rejectReason}; " +
                        if (pending.fallbackStop != null) {
                            "engine-held stop armed at ${pending.fallbackStop.stopPrice.toPlainString()}"
                        } else {
                            "engine-managed protection remains active"
                        },
                )
            }
            is RatchetPositionModification ->
                reportProtectionFailure(
                    pending.strategyId,
                    "venue rejected stop ratchet ${pending.orderId} at ${pending.stopLoss} " +
                        "for ticket ${pending.ticket}: ${event.rejectReason}; engine trigger remains active",
                )
        }
    }

    private fun reportProtectionFailure(
        strategyId: String,
        message: String,
    ) {
        log.error("position protection failure: {}", message)
        runCatching { onProtectionFailure(strategyId, message) }
            .onFailure { log.error("stack protection alert failed for strategy {}", strategyId, it) }
    }

    private fun armFillAnchoredFallbackStop(
        stop: OrderRequest.Stop,
        ticket: String,
    ) {
        if (orders.containsKey(stop.id)) return
        val now = clock.now()
        val managed =
            ManagedOrder(
                id = stop.id,
                request = stop,
                state = OrderState.PENDING,
                createdAt = now,
                lastUpdatedAt = now,
            )
        orders[stop.id] = managed
        indexLive(managed)
        engineHeldCloseTickets[stop.id] = ticket
        registerExposure(stop)
        persistAll()
    }

    private fun attachLayerSl(
        stackId: String,
        layerOrderId: String,
        fillPrice: BigDecimal,
        engineHeldCloseTicket: String? = null,
    ): BigDecimal? {
        val state = stacks.get(stackId) ?: return null
        val slAst = state.outerBracket?.stopLoss ?: return null
        val parent = (orders[stackId]?.request as? OrderRequest.Stack) ?: return null
        val exitSide = if (parent.side == Side.BUY) Side.SELL else Side.BUY
        val slPrice = computeChildPrice(slAst, parent.side, fillPrice, isStopLoss = true)
        val layerEntry = orders[layerOrderId] ?: return null
        val slId = "$layerOrderId-sl"
        val slReq =
            OrderRequest.Stop(
                id = slId,
                symbol = parent.symbol,
                side = exitSide,
                quantity =
                    layerEntry.cumulativeFilledQuantity.takeIf { it.signum() > 0 }
                        ?: layerEntry.request.quantity,
                stopPrice = slPrice,
                timeInForce = parent.timeInForce,
                timestamp = clock.now(),
                strategyId = parent.strategyId,
                legIntent = layerEntry.request.exitLegIntent(),
            )
        val now = clock.now()
        track(
            ManagedOrder(
                id = slId,
                request = slReq,
                state = OrderState.CREATED,
                parentClientOrderId = layerOrderId,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        update(layerOrderId) {
            it.copy(childClientOrderIds = it.childClientOrderIds + slId, lastUpdatedAt = now)
        }
        if (engineHeldCloseTicket != null) {
            engineHeldCloseTickets[slId] = engineHeldCloseTicket
            update(slId) { it.copy(state = OrderState.PENDING, lastUpdatedAt = clock.now()) }
        } else {
            dispatch(slReq)
        }
        return (fillPrice - slPrice).abs()
    }

    private fun attachLayerTp(
        stackId: String,
        layerOrderId: String,
        fillPrice: BigDecimal,
        slDistance: BigDecimal?,
    ): Boolean {
        val state = stacks.get(stackId) ?: return false
        val tpAst = state.outerBracket?.takeProfit ?: return false
        val parent = (orders[stackId]?.request as? OrderRequest.Stack) ?: return false
        val tpPrice = computeChildPrice(tpAst, parent.side, fillPrice, isStopLoss = false, slDistance = slDistance)
        val tpId = "$layerOrderId-tp"
        val exitSide = if (parent.side == Side.BUY) Side.SELL else Side.BUY
        val layerEntry = orders[layerOrderId] ?: return false
        val tpReq =
            OrderRequest.Limit(
                id = tpId,
                symbol = parent.symbol,
                side = exitSide,
                quantity = layerEntry.request.quantity,
                limitPrice = tpPrice,
                timeInForce = parent.timeInForce,
                timestamp = clock.now(),
                strategyId = parent.strategyId,
                legIntent = layerEntry.request.exitLegIntent(),
            )
        val now = clock.now()
        track(
            ManagedOrder(
                id = tpId,
                request = tpReq,
                state = OrderState.CREATED,
                parentClientOrderId = layerOrderId,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        update(layerOrderId) {
            it.copy(childClientOrderIds = it.childClientOrderIds + tpId, lastUpdatedAt = now)
        }
        dispatch(tpReq)
        return true
    }

    private fun computeChildPrice(
        childPrice: com.qkt.dsl.ast.ChildPriceAst,
        side: Side,
        fillPrice: BigDecimal,
        isStopLoss: Boolean,
        slDistance: BigDecimal? = null,
    ): BigDecimal {
        val sign =
            if (side == Side.BUY) {
                if (isStopLoss) BigDecimal("-1") else BigDecimal("1")
            } else {
                if (isStopLoss) BigDecimal("1") else BigDecimal("-1")
            }
        return when (childPrice) {
            is com.qkt.dsl.ast.ChildBy -> {
                val distance = evaluateAt(childPrice.distance, fillPrice)
                (fillPrice + distance.multiply(sign)).setScale(Money.SCALE, Money.ROUNDING)
            }
            is com.qkt.dsl.ast.ChildAt -> evaluateAt(childPrice.price, fillPrice).setScale(Money.SCALE, Money.ROUNDING)
            is com.qkt.dsl.ast.ChildPct -> {
                val percent = evaluateAt(childPrice.percent, fillPrice)
                val fraction = BracketPercent.fraction(percent, isStopLoss)
                val distance = fillPrice.multiply(fraction, Money.CONTEXT)
                (fillPrice + distance.multiply(sign)).setScale(Money.SCALE, Money.ROUNDING)
            }
            is com.qkt.dsl.ast.ChildRr -> {
                require(!isStopLoss) { "RR is only valid for TAKE PROFIT, not STOP LOSS" }
                val sl =
                    slDistance
                        ?: error("ChildRr requires a resolvable STOP LOSS distance from outerBracket")
                val multiplier = evaluateAt(childPrice.multiplier, fillPrice)
                val distance = sl.multiply(multiplier, Money.CONTEXT)
                (fillPrice + distance.multiply(sign)).setScale(Money.SCALE, Money.ROUNDING)
            }
            is com.qkt.dsl.ast.ChildArmedTrail -> {
                require(isStopLoss) { "ChildArmedTrail is only valid for STOP LOSS, not TAKE PROFIT" }
                // Pre-arm stop level: `fillPrice ± trailDistance`. The armed/trailing
                // behaviour is gated separately via [StopLossSpec.ArmedTrail] in OrderManager's
                // tick loop; this path computes the static pre-arm level only.
                val distance = evaluateAt(childPrice.trailDistance, fillPrice)
                (fillPrice + distance.multiply(sign)).setScale(Money.SCALE, Money.ROUNDING)
            }
        }
    }

    private fun evaluateStackFlat(e: BrokerEvent.OrderFilled) {
        val managed = orders[e.clientOrderId] ?: return
        val parentId = managed.parentClientOrderId ?: return
        val parent = orders[parentId] ?: return
        val stackId =
            if (parent.request is OrderRequest.Stack) {
                // POSITION_MODIFY venues report a position close under the layer entry's own id.
                // Its side is opposite the entry; same-side events are the original layer fill.
                if (e.side == managed.request.side) return
                stacks.recordLayerCloseFill(e.clientOrderId, e.quantity) ?: return
            } else {
                // Engine-decomposed SL/TP fills are children of the layer entry.
                stacks.markLayerClosed(parentId) ?: return
            }
        val state = stacks.get(stackId) ?: return
        if (state.filledLayerIds.size == state.closedLayerIds.size && state.filledLayerIds.isNotEmpty()) {
            cancelStackPending(stackId)
            stacks.terminate(stackId)
        }
    }

    private fun cancelStackPending(stackId: String) {
        val state = stacks.get(stackId) ?: return
        for (pid in state.pendingLayerIds.toList()) cancel(pid)
    }

    private fun materializePendingLayers(
        stackId: String,
        anchor: BigDecimal,
    ) {
        val state = stacks.get(stackId) ?: return
        val parent =
            (orders[stackId]?.request as? OrderRequest.Stack)
                ?: error("Stack request not tracked for $stackId")
        for (layer in state.plan.layers.drop(1)) {
            val triggerPrice = resolveTriggerPrice(layer.trigger, anchor)
            val layerOrderId = "$stackId-l${layer.index}"
            val qty = resolveLayerQuantity(layer)
            val pending = buildLayerOrder(layerOrderId, parent, layer, qty, triggerPrice)
            val now = clock.now()
            track(
                ManagedOrder(
                    id = layerOrderId,
                    request = pending,
                    state = OrderState.CREATED,
                    parentClientOrderId = stackId,
                    createdAt = now,
                    lastUpdatedAt = now,
                ),
            )
            stacks.addPending(stackId, layerOrderId)
            registerExposure(pending)
            log.info(
                "stack pending stack_id={} strategy_id={} layer={} qty={} trigger={} side={}",
                stackId,
                parent.strategyId,
                layer.index,
                qty,
                triggerPrice,
                parent.side,
            )
            val blockReason = engineHeldSubmissionBlockReason(pending)
            if (blockReason == null) {
                dispatch(pending)
            } else {
                rejectEngineHeld(pending, blockReason)
            }
        }
    }

    private fun resolveTriggerPrice(
        trigger: com.qkt.execution.LayerTrigger,
        anchor: BigDecimal,
    ): BigDecimal {
        val at = (trigger as? At) ?: error("non-Immediate triggers must be At")
        return evaluateAt(at.price, anchor)
    }

    private fun referencesStackEntryRef(expr: ExprAst): Boolean =
        when (expr) {
            is StackEntryRef -> true
            is BinaryOp -> referencesStackEntryRef(expr.lhs) || referencesStackEntryRef(expr.rhs)
            else -> false
        }

    private fun evaluateAt(
        expr: ExprAst,
        anchor: BigDecimal,
    ): BigDecimal =
        when (expr) {
            is StackEntryRef -> anchor
            is NumLit -> expr.value
            is BinaryOp -> {
                val l = evaluateAt(expr.lhs, anchor)
                val r = evaluateAt(expr.rhs, anchor)
                when (expr.op) {
                    BinOp.ADD -> l + r
                    BinOp.SUB -> l - r
                    BinOp.MUL -> l * r
                    BinOp.DIV -> l.divide(r, Money.CONTEXT)
                    else -> error("unsupported op in stack trigger: ${expr.op}")
                }
            }

            else -> error("unsupported trigger expression: $expr")
        }

    /** Active protective orders that require ticks on the engine thread to trigger. */
    fun engineHeldProtectiveStopCount(): Int =
        orders.values.count { managed ->
            !managed.state.isTerminal &&
                (
                    managed.id in engineHeldCloseTickets ||
                        isPersistentManagedStop(managed.request)
                )
        }

    private fun resolveLayerQuantity(layer: LayerSpec): BigDecimal {
        layer.resolvedQuantity?.let { return it }
        // Fallback: supports test code that builds LayerSpec by hand without going through
        // ActionCompiler. Only literal-qty sizing is supported in this path.
        val sizing = layer.sizing
        if (sizing is SizeQty) {
            val n =
                sizing.expr as? NumLit
                    ?: error("STACK layer qty must be a literal in tests that bypass ActionCompiler")
            return n.value
        }
        error(
            "STACK non-qty sizing (RISK/NOTIONAL/EQUITY%/BALANCE%) requires resolution by ActionCompiler. " +
                "If building LayerSpec manually for testing, use SizeQty(NumLit). " +
                "If reaching this in production, ActionCompiler did not populate LayerSpec.resolvedQuantity.",
        )
    }

    private fun buildLayerOrder(
        layerId: String,
        parent: OrderRequest.Stack,
        layer: LayerSpec,
        qty: BigDecimal,
        triggerPrice: BigDecimal?,
    ): OrderRequest {
        val intent = layerEntryIntent(layerId, parent.symbol)
        return when {
            triggerPrice == null ->
                OrderRequest.Market(
                    id = layerId,
                    symbol = parent.symbol,
                    side = parent.side,
                    quantity = qty,
                    timeInForce = parent.timeInForce,
                    timestamp = clock.now(),
                    strategyId = parent.strategyId,
                    legIntent = intent,
                )
            layer.orderType is com.qkt.dsl.ast.Limit ->
                OrderRequest.Limit(
                    id = layerId,
                    symbol = parent.symbol,
                    side = parent.side,
                    quantity = qty,
                    limitPrice = triggerPrice,
                    timeInForce = parent.timeInForce,
                    timestamp = clock.now(),
                    strategyId = parent.strategyId,
                    legIntent = intent,
                )
            else ->
                OrderRequest.Stop(
                    id = layerId,
                    symbol = parent.symbol,
                    side = parent.side,
                    quantity = qty,
                    stopPrice = triggerPrice,
                    timeInForce = parent.timeInForce,
                    timestamp = clock.now(),
                    strategyId = parent.strategyId,
                    legIntent = intent,
                )
        }
    }

    /**
     * A pyramiding layer is its own ticket on a hedging venue and nets into the book elsewhere —
     * the same rule the planner applies to a strategy-emitted entry.
     */
    private fun layerEntryIntent(
        layerId: String,
        symbol: String,
    ): LegIntent =
        if (positionMode(symbol) == PositionAccountingMode.HEDGING) {
            LegIntent.Open(layerId, LegRole.INDEPENDENT)
        } else {
            LegIntent.Net
        }

    /**
     * Best-effort entry-price estimate for an [OrderRequest.Bracket]'s SL/TP children.
     * Stop/Limit/IfTouched entries carry their intended trigger as a field; Market
     * entries fall back to the last observed market price.
     */
    private fun bracketEntryEstimate(req: OrderRequest.Bracket): BigDecimal =
        when (val entry = req.entry) {
            is OrderRequest.Stop -> entry.stopPrice
            is OrderRequest.Limit -> entry.limitPrice
            is OrderRequest.IfTouched -> entry.triggerPrice
            is OrderRequest.StopLimit -> entry.stopPrice
            else ->
                lastObservedPrice[req.symbol]
                    ?: priceProvider.lastPrice(req.symbol)
                    ?: error("Cannot estimate entry price for bracket ${req.id}: no last price for ${req.symbol}")
        }

    private fun resolveBracketAtFill(
        req: OrderRequest.Bracket,
        fillPrice: BigDecimal,
    ): OrderRequest.Bracket {
        val stop =
            req.stopLossAst?.let { ast ->
                when (ast) {
                    is com.qkt.dsl.ast.ChildArmedTrail ->
                        StopLossSpec.ArmedTrail(
                            evaluateAt(ast.trailDistance, fillPrice),
                            evaluateAt(ast.mfeThreshold, fillPrice),
                        )
                    is com.qkt.dsl.ast.ChildBy ->
                        if (req.stopLoss !is StopLossSpec.Fixed) {
                            req.stopLoss
                        } else {
                            StopLossSpec.Fixed(
                                computeChildPrice(ast, req.side, fillPrice, isStopLoss = true),
                            )
                        }
                    else ->
                        StopLossSpec.Fixed(
                            computeChildPrice(ast, req.side, fillPrice, isStopLoss = true),
                        )
                }
            } ?: req.stopLoss
        val stopDistance =
            when (stop) {
                is StopLossSpec.Fixed -> (fillPrice - stop.price).abs()
                is StopLossSpec.ArmedTrail -> stop.trailDistance
                is StopLossSpec.SteppedStop -> stop.initialDistance
                is StopLossSpec.TimeTighten -> stop.initialDistance
            }
        val takeProfit =
            req.takeProfitAst?.let {
                computeChildPrice(it, req.side, fillPrice, isStopLoss = false, slDistance = stopDistance)
            } ?: req.takeProfit
        return req.copy(takeProfit = takeProfit, stopLoss = stop)
    }

    private fun bracketExitOco(
        req: OrderRequest.Bracket,
        fillPrice: BigDecimal,
        fillQuantity: BigDecimal,
    ): OrderRequest.StandaloneOCO {
        val resolved = resolveBracketAtFill(req, fillPrice)
        // Exits must never exceed what actually filled — a venue partial booked at its
        // real volume (#615) would otherwise get exits sized to the full request.
        val exitQuantity = resolved.quantity.min(fillQuantity)
        val exitSide = if (resolved.side == Side.BUY) Side.SELL else Side.BUY
        val exit = resolved.exitLegIntent()
        val tp =
            OrderRequest.Limit(
                "${resolved.id}-tp",
                resolved.symbol,
                exitSide,
                exitQuantity,
                resolved.takeProfit,
                resolved.timeInForce,
                clock.now(),
                resolved.strategyId,
                legIntent = exit,
            )
        val sl =
            when (val spec = resolved.stopLoss) {
                is StopLossSpec.Fixed ->
                    OrderRequest.Stop(
                        "${resolved.id}-sl",
                        resolved.symbol,
                        exitSide,
                        exitQuantity,
                        spec.price,
                        resolved.timeInForce,
                        clock.now(),
                        resolved.strategyId,
                        legIntent = exit,
                    )
                is StopLossSpec.ArmedTrail ->
                    OrderRequest.ArmedTrailingStop(
                        "${resolved.id}-sl",
                        resolved.symbol,
                        exitSide,
                        exitQuantity,
                        fillPrice,
                        spec.trailDistance,
                        spec.mfeThreshold,
                        resolved.timeInForce,
                        clock.now(),
                        resolved.strategyId,
                        legIntent = exit,
                    )
                is StopLossSpec.SteppedStop ->
                    OrderRequest.SteppedStop(
                        id = "${resolved.id}-sl",
                        symbol = resolved.symbol,
                        side = exitSide,
                        quantity = exitQuantity,
                        entryPrice = fillPrice,
                        initialDistance = spec.initialDistance,
                        steps = spec.steps,
                        timeInForce = resolved.timeInForce,
                        timestamp = clock.now(),
                        strategyId = resolved.strategyId,
                        legIntent = exit,
                    )
                is StopLossSpec.TimeTighten ->
                    OrderRequest.TimeTighteningStop(
                        id = "${resolved.id}-sl",
                        symbol = resolved.symbol,
                        side = exitSide,
                        quantity = exitQuantity,
                        entryPrice = fillPrice,
                        initialDistance = spec.initialDistance,
                        tightenBy = spec.tightenBy,
                        intervalMs = spec.intervalMs,
                        floorDistance = spec.floorDistance,
                        timeInForce = resolved.timeInForce,
                        timestamp = clock.now(),
                        strategyId = resolved.strategyId,
                        legIntent = exit,
                    )
            }
        return OrderRequest.StandaloneOCO(
            "${resolved.id}-oco",
            resolved.symbol,
            exitSide,
            exitQuantity,
            tp,
            sl,
            resolved.timeInForce,
            clock.now(),
            resolved.strategyId,
        )
    }

    private fun submitBracketFallback(req: OrderRequest.Bracket): SubmitAck {
        val exitSide = if (req.side == Side.BUY) Side.SELL else Side.BUY
        val exit = req.exitLegIntent()
        val tp =
            OrderRequest.Limit(
                id = "${req.id}-tp",
                symbol = req.symbol,
                side = exitSide,
                quantity = req.quantity,
                limitPrice = req.takeProfit,
                timeInForce = req.timeInForce,
                timestamp = clock.now(),
                strategyId = req.strategyId,
                legIntent = exit,
            )
        // Pick the SL child shape per the bracket's stop spec. Fixed → a plain Stop at
        // the resolved price. ArmedTrail → an engine-managed ArmedTrailingStop whose
        // entry price is the bracket entry's intended fill, and whose pre-arm/post-arm
        // levels are computed by trailLevel on each tick. See #48.
        val sl: OrderRequest =
            when (val spec = req.stopLoss) {
                is StopLossSpec.Fixed ->
                    OrderRequest.Stop(
                        id = "${req.id}-sl",
                        symbol = req.symbol,
                        side = exitSide,
                        quantity = req.quantity,
                        stopPrice = spec.price,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        legIntent = exit,
                    )
                is StopLossSpec.ArmedTrail -> {
                    val entryPrice = bracketEntryEstimate(req)
                    OrderRequest.ArmedTrailingStop(
                        id = "${req.id}-sl",
                        symbol = req.symbol,
                        side = exitSide,
                        quantity = req.quantity,
                        entryPrice = entryPrice,
                        trailDistance = spec.trailDistance,
                        mfeThreshold = spec.mfeThreshold,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        legIntent = exit,
                    )
                }
                is StopLossSpec.SteppedStop -> {
                    val entryPrice = bracketEntryEstimate(req)
                    OrderRequest.SteppedStop(
                        id = "${req.id}-sl",
                        symbol = req.symbol,
                        side = exitSide,
                        quantity = req.quantity,
                        entryPrice = entryPrice,
                        initialDistance = spec.initialDistance,
                        steps = spec.steps,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        legIntent = exit,
                    )
                }
                is StopLossSpec.TimeTighten -> {
                    val entryPrice = bracketEntryEstimate(req)
                    OrderRequest.TimeTighteningStop(
                        id = "${req.id}-sl",
                        symbol = req.symbol,
                        side = exitSide,
                        quantity = req.quantity,
                        entryPrice = entryPrice,
                        initialDistance = spec.initialDistance,
                        tightenBy = spec.tightenBy,
                        intervalMs = spec.intervalMs,
                        floorDistance = spec.floorDistance,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        legIntent = exit,
                    )
                }
            }
        val oco =
            OrderRequest.StandaloneOCO(
                id = "${req.id}-oco",
                symbol = req.symbol,
                side = exitSide,
                quantity = req.quantity,
                leg1 = tp,
                leg2 = sl,
                timeInForce = req.timeInForce,
                timestamp = clock.now(),
                strategyId = req.strategyId,
            )
        val oto =
            OrderRequest.OTO(
                id = req.id,
                symbol = req.symbol,
                side = req.side,
                quantity = req.quantity,
                parent = req.entry.withStrategyId(req.strategyId),
                children = listOf(oco),
                timeInForce = req.timeInForce,
                timestamp = clock.now(),
                strategyId = req.strategyId,
            )
        preFillBrackets[req.entry.id] = req
        if (req.takeProfitAst != null || req.stopLossAst != null) {
            fillAnchoredFallbackBrackets[req.entry.id] = req
        }
        orders.remove(req.id)
        liveOrderIds.remove(req.id)
        liveBySymbol[req.symbol]?.remove(req.id)
        gtdLive.remove(req.id)
        return submit(oto)
    }

    /**
     * Ship an armed-trail bracket to a venue that holds attached SL/TP on the position.
     *
     * The bracket goes to the broker keyed under the ENTRY id, so [MT5OrderTranslator] attaches
     * the pre-arm SL (`entry ∓ trailDistance`, via the bracket's [StopLossSpec.ArmedTrail]) and
     * the TP to the resulting position — the venue then closes that exact ticket when a level is
     * hit (no counter on a hedging account) and keeps protecting it even if qkt is offline.
     * Keying under the entry id (not the bracket id) means the fill — and the ticket it carries —
     * flow through the same entry.id paths the position tracking already uses (sibling-cancel,
     * leg intent on the entry, poller close attribution).
     *
     * The engine still runs the trail on top: the [OrderRequest.ArmedTrailingStop] is dispatched
     * when the entry fills (via [pendingChildren]) and, once armed, fires a close-by-ticket at the
     * tightened level — finer than the static venue stop, which remains the offline backstop.
     */
    private fun submitBracketAttached(req: OrderRequest.Bracket): SubmitAck {
        val now = clock.now()
        // Ship keyed under the ENTRY id so the venue attaches the SL/TP to the position AND the
        // fill — with its ticket — flows through the same entry.id paths the position tracking
        // uses (the entry's leg intent, sibling-cancel, poller close
        // attribution). A native bracket keyed under its own id would fill under the bracket id
        // and silently miss those registrations.
        val attached = req.copy(id = req.entry.id)
        preFillBrackets[attached.id] = req
        if (req.takeProfitAst != null || req.stopLossAst != null) {
            fillAnchoredAttachedBrackets[attached.id] = req
        }
        // An armed trail is engine-managed on top of the venue's static pre-arm stop: dispatched
        // on the entry fill, it fires close-by-ticket at the tightened level. A fixed bracket has
        // no engine exit — the venue's attached SL/TP closes it outright.
        val managedStop = buildAttachedManagedStop(req, now)
        update(req.id) {
            it.copy(
                state = OrderState.WORKING,
                childClientOrderIds = listOfNotNull(attached.id, managedStop?.id),
                lastUpdatedAt = now,
            )
        }
        track(
            ManagedOrder(
                id = attached.id,
                request = attached,
                state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        if (managedStop != null) {
            track(
                ManagedOrder(
                    id = managedStop.id,
                    request = managedStop,
                    state = OrderState.CREATED,
                    parentClientOrderId = req.id,
                    createdAt = now,
                    lastUpdatedAt = now,
                ),
            )
            // Arm the trail only once the position exists — dispatched on the entry's fill.
            pendingChildren[attached.id] = listOf(managedStop)
        }
        registerExposure(attached)
        val ack = submitToBroker(attached)
        return SubmitAck(req.id, req.id, accepted = ack.accepted, rejectReason = ack.rejectReason)
    }

    private fun buildAttachedManagedStop(
        req: OrderRequest.Bracket,
        now: Long,
    ): OrderRequest? {
        val exitSide = if (req.side == Side.BUY) Side.SELL else Side.BUY
        val exit = req.exitLegIntent()
        return when (val spec = req.stopLoss) {
            is StopLossSpec.Fixed -> null
            is StopLossSpec.ArmedTrail ->
                OrderRequest.ArmedTrailingStop(
                    id = "${req.id}-sl",
                    symbol = req.symbol,
                    side = exitSide,
                    quantity = req.quantity,
                    entryPrice = bracketEntryEstimate(req),
                    trailDistance = spec.trailDistance,
                    mfeThreshold = spec.mfeThreshold,
                    timeInForce = req.timeInForce,
                    timestamp = now,
                    strategyId = req.strategyId,
                    legIntent = exit,
                )
            is StopLossSpec.SteppedStop ->
                OrderRequest.SteppedStop(
                    id = "${req.id}-sl",
                    symbol = req.symbol,
                    side = exitSide,
                    quantity = req.quantity,
                    entryPrice = bracketEntryEstimate(req),
                    initialDistance = spec.initialDistance,
                    steps = spec.steps,
                    timeInForce = req.timeInForce,
                    timestamp = now,
                    strategyId = req.strategyId,
                    legIntent = exit,
                )
            is StopLossSpec.TimeTighten ->
                OrderRequest.TimeTighteningStop(
                    id = "${req.id}-sl",
                    symbol = req.symbol,
                    side = exitSide,
                    quantity = req.quantity,
                    entryPrice = bracketEntryEstimate(req),
                    initialDistance = spec.initialDistance,
                    tightenBy = spec.tightenBy,
                    intervalMs = spec.intervalMs,
                    floorDistance = spec.floorDistance,
                    timeInForce = req.timeInForce,
                    timestamp = now,
                    strategyId = req.strategyId,
                    legIntent = exit,
                )
        }
    }

    private fun submitToBroker(request: OrderRequest): SubmitAck {
        val expiresAt = request.expiresAt
        if (expiresAt != null && expiresAt <= clock.now()) return rejectExpiredBeforeSubmit(request, expiresAt)
        update(request.id) { it.copy(state = OrderState.SUBMITTED, lastUpdatedAt = clock.now()) }
        persistSubmissionIntent(request.strategyId)
        val ack = broker.submit(request)
        if (!ack.accepted && orders[request.id]?.state?.isTerminal != true) {
            update(request.id) { it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now()) }
            exposureEntries.remove(request.id)
        }
        return ack
    }

    // A GTD deadline at or past the current clock can only round-trip into a venue
    // rejection (MT5 retcode 10022), so it is refused here with both clocks in the
    // reason — a bar-clock vs wall-clock divergence (#811) is visible at its first
    // occurrence instead of masquerading as a venue error.

    /**
     * A bracket whose absolute protection is already crossed at submit can only round-trip
     * into a venue rejection (MT5 retcode 10016 Invalid stops) — or, in a simulated tier,
     * fill and instantly stop out, which live would never do (#1076). Refuse locally with
     * the levels in the reason.
     */
    private fun rejectCrossedProtection(
        request: OrderRequest.Bracket,
        reference: BigDecimal,
        level: BigDecimal,
        leg: String,
    ): SubmitAck {
        val reason =
            "invalid stops: $leg $level already crossed for ${request.side} at reference $reference " +
                "(venue would reject, retcode 10016)"
        log.warn("order {} {} — rejected locally, not sent to broker", request.id, reason)
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = request.id,
                brokerOrderId = null,
                reason = reason,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(clientOrderId = request.id, brokerOrderId = null, accepted = false, rejectReason = reason)
    }

    private fun rejectExpiredBeforeSubmit(
        request: OrderRequest,
        expiresAt: Long,
    ): SubmitAck {
        val now = clock.now()
        val reason = "expired before submit: expiresAt=$expiresAt now=$now"
        log.warn("order {} {} — rejected locally, not sent to broker", request.id, reason)
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = request.id,
                brokerOrderId = null,
                reason = reason,
                strategyId = request.strategyId,
                timestamp = now,
            ),
        )
        return SubmitAck(clientOrderId = request.id, brokerOrderId = null, accepted = false, rejectReason = reason)
    }

    private fun submitRegisteredToBroker(request: OrderRequest): SubmitAck {
        val entry = exposureEntryRequest(request)
        val existing = if (entry.id == request.id) null else exposureEntries.remove(entry.id)
        registerExposure(request, existing?.groupId)
        return submitToBroker(request)
    }

    private fun submitOto(req: OrderRequest.OTO): SubmitAck {
        val now = clock.now()
        val childIds = req.children.map { it.id }
        update(req.id) {
            it.copy(
                state = OrderState.WORKING,
                childClientOrderIds = listOf(req.parent.id) + childIds,
                lastUpdatedAt = now,
            )
        }
        track(
            ManagedOrder(
                id = req.parent.id,
                request = req.parent,
                state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        for (child in req.children) {
            track(
                ManagedOrder(
                    id = child.id,
                    request = child,
                    state = OrderState.CREATED,
                    parentClientOrderId = req.id,
                    createdAt = now,
                    lastUpdatedAt = now,
                ),
            )
        }
        pendingChildren[req.parent.id] = req.children
        pendingOtosByParent[req.parent.id] = req
        registerExposure(exposureEntryRequest(req.parent))
        dispatch(req.parent)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    private fun submitOco(req: OrderRequest.StandaloneOCO): SubmitAck {
        val now = clock.now()
        update(req.id) {
            it.copy(
                state = OrderState.WORKING,
                groupId = req.id,
                childClientOrderIds = listOf(req.leg1.id, req.leg2.id),
                lastUpdatedAt = now,
            )
        }
        for (leg in listOf(req.leg1, req.leg2)) {
            track(
                ManagedOrder(
                    id = leg.id,
                    request = leg,
                    state = OrderState.CREATED,
                    parentClientOrderId = req.id,
                    groupId = req.id,
                    createdAt = now,
                    lastUpdatedAt = now,
                ),
            )
        }
        // Sibling link keyed by the id each leg's fill arrives under, not the leg's own id.
        // A Bracket leg is placed as an OTO whose parent is the inner entry, so the broker
        // fills `Bracket.entry` (a distinct id) — keying by the bracket id would leave the
        // link unreachable and the sibling would never cancel on fill. Leaf legs (Stop/Limit)
        // fill under their own id, so this is a no-op for them. Acceptances/rejections arrive
        // under the same id, so the sequence below is keyed by it too.
        val leg1AckId = ocoFillId(req.leg1)
        val leg2AckId = ocoFillId(req.leg2)
        registerExposure(exposureEntryRequest(req.leg1), req.id)
        registerExposure(exposureEntryRequest(req.leg2), req.id)
        siblings[leg1AckId] = listOf(leg2AckId)
        siblings[leg2AckId] = listOf(leg1AckId)
        emulatedOcoGroupByLeg[leg1AckId] = req.id
        emulatedOcoGroupByLeg[leg2AckId] = req.id

        // Event-driven sequencing: place leg1 now; leg2 only once the venue accepts leg1
        // (in [advanceOcoOnAccept]). A leg1 rejection abandons the OCO with leg2 never sent —
        // there is no one-legged window. With a synchronous broker the acceptance fires inline
        // during dispatch, so the whole OCO resolves here re-entrantly; with an async broker
        // the result follows later on the bus. Either way the OCO's tracked state is the truth.
        val seq = OcoSequence(req.id, req.leg1, leg1AckId, req.leg2, leg2AckId)
        ocoByLeg1[leg1AckId] = seq
        ocoByLeg2[leg2AckId] = seq

        val ack1 = dispatch(req.leg1)
        if (orders[req.id]?.state == OrderState.REJECTED) {
            return SubmitAck(req.id, req.id, accepted = false, rejectReason = "leg ${req.leg1.id} rejected")
        }
        if (!ack1.accepted) {
            // Local rejection that carried no event (e.g. a capability reject) — abandon the
            // OCO; leg2 was never dispatched.
            exposureEntries.remove(leg2AckId)
            clearOcoSequence(seq)
            return rejectOco(req.id, "leg ${req.leg1.id} rejected: ${ack1.rejectReason ?: "unknown"}")
        }
        return SubmitAck(req.id, req.id, accepted = true)
    }

    /**
     * Advance any OCO whose leg the venue just accepted. Accepting leg1 releases leg2 (held
     * back so a leg1 rejection can't leave a one-legged OCO); accepting leg2 confirms its
     * venue ticket and fires a cancel that was deferred because leg1 filled while leg2 was
     * still unacknowledged.
     */
    private fun advanceOcoOnAccept(ackId: String) {
        ocoByLeg1[ackId]?.let { seq ->
            if (!seq.leg2Placed && orders[seq.ocoId]?.state?.isTerminal != true) {
                seq.leg2Placed = true
                dispatch(seq.leg2)
            }
        }
        ocoByLeg2[ackId]?.let { seq ->
            seq.leg2Confirmed = true
            if (seq.leg2PendingCancel) {
                seq.leg2PendingCancel = false
                cancel(seq.leg2.id)
                clearOcoSequence(seq)
            }
        }
    }

    /**
     * Abandon an OCO whose leg the venue rejected. A leg1 rejection means leg2 was never sent
     * — nothing to unwind. A leg2 rejection cancels the still-live leg1.
     */
    private fun failOcoOnReject(ackId: String) {
        ocoByLeg1[ackId]?.let { seq ->
            if (!seq.leg2Placed) {
                exposureEntries.remove(seq.leg2AckId)
                clearOcoSequence(seq)
                rejectOco(seq.ocoId, "leg ${seq.leg1.id} rejected")
                return
            }
        }
        ocoByLeg2[ackId]?.let { seq ->
            clearOcoSequence(seq)
            cancel(seq.leg1.id)
            rejectOco(seq.ocoId, "leg ${seq.leg2.id} rejected")
        }
    }

    private fun clearOcoSequence(seq: OcoSequence) {
        ocoByLeg1.remove(seq.leg1AckId)
        ocoByLeg2.remove(seq.leg2AckId)
    }

    private fun clearOcoSequenceFor(ackId: String) {
        (ocoByLeg1[ackId] ?: ocoByLeg2[ackId])?.let { clearOcoSequence(it) }
    }

    /**
     * The clientOrderId under which [leg]'s fill is reported. A Bracket leg is placed as an
     * OTO whose parent is `Bracket.entry`, so the broker fills the inner entry — its id, not
     * the bracket wrapper's. Leaf legs (Stop/Limit) fill under their own id. Mirrors the
     * compiler's [com.qkt.dsl.compile.ActionCompiler.parentClientOrderIdFor].
     */
    private fun ocoFillId(leg: OrderRequest): String = (leg as? OrderRequest.Bracket)?.entry?.id ?: leg.id

    private fun rejectOco(
        ocoId: String,
        reason: String,
    ): SubmitAck {
        update(ocoId) { it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now()) }
        return SubmitAck(ocoId, ocoId, accepted = false, rejectReason = reason)
    }

    private fun holdPending(request: OrderRequest): SubmitAck {
        update(request.id) { it.copy(state = OrderState.PENDING, lastUpdatedAt = clock.now()) }
        if (request is OrderRequest.TrailingStop || request is OrderRequest.TrailingStopLimit) {
            val seed = lastObservedPrice[request.symbol] ?: priceProvider.lastPrice(request.symbol)
            if (seed != null) trailingHwm[request.id] = seed
        }
        if (request is OrderRequest.ArmedTrailingStop) {
            // Seed hwm at the entry price — MFE = |hwm - entry| starts at 0. Each tick
            // [updateTrailingHwm] will move hwm toward the favorable side. Pre-arm the
            // stop sits at entry ± distance regardless of hwm; once armed, hwm leads.
            trailingHwm[request.id] = request.entryPrice
            armedTrailArmed[request.id] = false
        }
        if (request is OrderRequest.SteppedStop) {
            trailingHwm[request.id] = request.entryPrice
            steppedStopIndex[request.id] = 0
            managedStopLevel[request.id] =
                initialStopLevel(request.side, request.entryPrice, request.initialDistance)
        }
        if (request is OrderRequest.TimeTighteningStop) {
            timeTightenIntervals[request.id] = 0L
            managedStopLevel[request.id] =
                initialStopLevel(request.side, request.entryPrice, request.initialDistance)
        }
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = request.id,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = request.id,
            accepted = true,
        )
    }

    /**
     * Keep the live-order indexes in sync: a non-terminal order is live, a terminal one is not.
     * Alongside [liveOrderIds]/[liveBySymbol] this maintains [gtdLive] (orders with a deadline) so
     * the per-tick expiry sweep walks only deadline-bearing orders. `expiresAt` is fixed at
     * creation, so re-indexing an order whose state changed keeps the subset correct.
     */
    private fun indexLive(managed: ManagedOrder) {
        val symbol = managed.request.symbol
        val id = managed.id
        if (managed.state.isTerminal) {
            liveOrderIds.remove(id)
            liveBySymbol[symbol]?.remove(id)
            gtdLive.remove(id)
        } else {
            liveOrderIds.add(id)
            liveBySymbol.getOrPut(symbol) { LinkedHashSet() }.add(id)
            managed.request.expiresAt?.let { gtdLive[id] = it }
        }
    }

    /**
     * True while some active structure still points at [id], so reclaiming it would break a
     * later lookup: a pending timed-exit whose target is this order, or an active stack that
     * owns it as the parent, layer-one, or a pending/filled/closed layer. Per-order satellite
     * Most per-order satellite data is not a reference and is evicted on reclaim. A filled
     * client-emulated OCO leg is the exception: keep it until its sibling resolves so a late
     * second fill can still be identified and compensated after intervening ticks.
     */
    private fun isReferenced(id: String): Boolean {
        if (
            id in emulatedOcoGroupByLeg &&
            siblings[id].orEmpty().any { siblingId -> orders[siblingId]?.state?.isTerminal == false }
        ) {
            return true
        }
        if (timeExits.values.any { it.target.id == id }) return true
        for (s in stacks.all()) {
            if (id == s.id || id == s.layerOneOrderId) return true
            if (id in s.pendingLayerIds || id in s.filledLayerIds || id in s.closedLayerIds) return true
        }
        return false
    }

    /** Drop a dead, unreferenced order and all its order-keyed satellite state. */
    private fun reclaim(id: String) {
        val symbol = orders[id]?.request?.symbol
        orders.remove(id)
        liveOrderIds.remove(id)
        if (symbol != null) liveBySymbol[symbol]?.remove(id)
        gtdLive.remove(id)
        trailingHwm.remove(id)
        armedTrailArmed.remove(id)
        steppedStopIndex.remove(id)
        timeTightenIntervals.remove(id)
        managedStopLevel.remove(id)
        siblings.remove(id)
        pendingScaleOutsByBasis.remove(id)
        partialScaleOutPositionTickets.remove(id)
        cancellingScaleOutWrappers.remove(id)
        scaleOutByExitId.remove(id)
        ocoSiblingCancelStarted.remove(id)
        emulatedOcoGroupByLeg.remove(id)
        pendingChildren.remove(id)
        pendingOtosByParent.remove(id)
        engineHeldCloseTickets.remove(id)
        exposureEntries.remove(id)
    }

    /**
     * Reclaim terminal orders that nothing references. Processes each queued id once per drain;
     * a still-referenced id (e.g. a filled entry a pending timed-exit still points at) is
     * re-queued for a later pass, so per-drain cost tracks freshly-finished plus still-referenced
     * terminal orders. Only removes dead, unreferenced orders, so it can never change a trading
     * decision.
     */
    private fun runGc() {
        repeat(gcQueue.size) {
            val id = gcQueue.removeFirst()
            val managed = orders[id]
            when {
                managed == null -> Unit
                !managed.state.isTerminal -> Unit
                isReferenced(id) -> gcQueue.addLast(id)
                else -> reclaim(id)
            }
        }
    }

    private fun track(managed: ManagedOrder) {
        orders[managed.id] = managed
        managed.request.strategyId
            .takeIf { it.isNotBlank() }
            ?.let(persistedStrategies::add)
        indexLive(managed)
        persistAll()
    }

    private fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ): Boolean {
        val current = orders[id]
        if (current == null) {
            persistAll()
            return false
        }
        val updated = change(current)
        // A terminal outcome is immutable. Same-state metadata updates remain valid: a
        // filled stack entry still receives child ids when its protection is attached.
        if (current.state.isTerminal && updated.state != current.state) {
            log.error(
                "ignoring illegal terminal transition {} -> {} for order {} — terminal outcomes are immutable",
                current.state,
                updated.state,
                id,
            )
            return false
        }
        orders[id] = updated
        indexLive(updated)
        if (updated.state.isTerminal && !current.state.isTerminal) gcQueue.addLast(id)
        persistAll()
        return true
    }

    /**
     * Snapshot all active leaf orders and linked engine state per strategy.
     *
     * Routine mutation snapshots are best-effort so an asynchronous persistence failure does
     * not block event dispatch. Venue-bound intent takes the fail-closed synchronous path in
     * [persistSubmissionIntent].
     */
    private fun persistAll() {
        runCatching {
            val pendingByStrategy: MutableMap<String, MutableMap<String, com.qkt.execution.OrderRequest>> =
                mutableMapOf()
            val pairsByStrategy: MutableMap<String, MutableList<com.qkt.persistence.BracketPair>> = mutableMapOf()
            val unarmedChildren = unarmedChildIds()

            for ((id, managed) in orders) {
                if (!managed.state.isTerminal) {
                    val sid = managed.request.strategyId
                    if (sid.isBlank()) continue
                    // Composite parents are handled below or by their dedicated recovery state.
                    if (managed.request.isCompositeShape()) continue
                    if (id in unarmedChildren) continue
                    pendingByStrategy.getOrPut(sid) { mutableMapOf() }[id] = managed.request
                }
            }
            overlayPendingOtos(pendingByStrategy)
            overlayPendingScaleOuts(pendingByStrategy)
            for ((entryId, bracket) in preFillBrackets) {
                if (orders[entryId]?.state?.isTerminal == true) continue
                val sid = bracket.strategyId
                if (sid.isBlank()) continue
                pendingByStrategy.getOrPut(sid) { mutableMapOf() }[entryId] = bracket
            }
            for ((id, managed) in orders) {
                val bracket = managed.request as? OrderRequest.Bracket ?: continue
                if (managed.state.isTerminal || id in preFillBrackets || bracket in preFillBrackets.values) continue
                val sid = bracket.strategyId
                if (sid.isBlank()) continue
                pendingByStrategy.getOrPut(sid) { mutableMapOf() }[id] = bracket
            }
            for ((entryId, siblingIds) in siblings) {
                val entry = orders[entryId] ?: continue
                val sid = entry.request.strategyId
                if (sid.isBlank()) continue
                val sl =
                    siblingIds.firstOrNull {
                        it.contains("-sl") ||
                            (orders[it]?.request is com.qkt.execution.OrderRequest.Stop)
                    }
                val tp = siblingIds.firstOrNull { it != sl }
                pairsByStrategy.getOrPut(sid) { mutableListOf() }.add(
                    com.qkt.persistence.BracketPair(
                        entryClientOrderId = entryId,
                        stopLossClientOrderId = sl,
                        takeProfitClientOrderId = tp,
                        legId = null,
                    ),
                )
            }
            val ocoLegsByStrategy: MutableMap<String, MutableList<com.qkt.persistence.PersistedOcoLeg>> =
                mutableMapOf()
            for ((legId, siblingIds) in siblings) {
                val managed = orders[legId] ?: continue
                if (managed.state.isTerminal) continue
                val ticket = managed.brokerOrderId ?: continue
                val sid = managed.request.strategyId
                if (sid.isBlank()) continue
                ocoLegsByStrategy.getOrPut(sid) { mutableListOf() }.add(
                    com.qkt.persistence.PersistedOcoLeg(
                        clientOrderId = legId,
                        brokerOrderId = ticket,
                        strategyId = sid,
                        request = managed.request,
                        siblingIds = siblingIds,
                    ),
                )
            }
            val trailingStopsByStrategy = trailingStopsByStrategy()
            val strategies =
                (
                    persistedStrategies + pendingByStrategy.keys + pairsByStrategy.keys + ocoLegsByStrategy.keys +
                        trailingStopsByStrategy.keys
                ).toSet()
            for (sid in strategies) {
                persistIfChanged(sid, PENDING_SLOT, pendingByStrategy[sid] ?: emptyMap(), persistor::savePendingOrders)
                persistIfChanged(sid, PAIRS_SLOT, pairsByStrategy[sid] ?: emptyList(), persistor::saveBracketPairs)
                persistIfChanged(sid, OCO_SLOT, ocoLegsByStrategy[sid] ?: emptyList(), persistor::saveOcoLegs)
                persistIfChanged(
                    sid,
                    TRAILING_SLOT,
                    trailingStopsByStrategy[sid] ?: emptyList(),
                    persistor::saveTrailingStops,
                )
            }
            trailingStateDirty = false
        }
    }

    private fun <T : Any> persistIfChanged(
        strategyId: String,
        slot: String,
        value: T,
        save: (String, T) -> Unit,
    ) {
        val key = strategyId to slot
        if (lastPersisted[key] == value) return
        lastPersisted[key] = value
        save(strategyId, value)
    }

    private fun persistSubmissionIntent(strategyId: String) {
        if (strategyId.isBlank()) return
        persistedStrategies.add(strategyId)
        val active = recoveryPendingOrders(strategyId)
        lastPersisted[strategyId to PENDING_SLOT] = active
        persistor.savePendingOrdersSync(strategyId, active)
    }

    private fun recoveryPendingOrders(strategyId: String): Map<String, OrderRequest> {
        val unarmedChildren = unarmedChildIds()
        val result =
            orders
                .asSequence()
                .filter { (id, managed) ->
                    managed.request.strategyId == strategyId &&
                        !managed.state.isTerminal &&
                        !managed.request.isCompositeShape() &&
                        id !in unarmedChildren
                }.associateTo(linkedMapOf()) { (id, managed) -> id to managed.request }
        pendingOtosByParent.forEach { (parentId, oto) ->
            if (oto.strategyId == strategyId && orders[parentId]?.state?.isTerminal == false) {
                result[parentId] = oto
            }
        }
        pendingScaleOutsByBasis.forEach { (basisId, scaleOut) ->
            if (scaleOut.strategyId == strategyId && orders[basisId]?.state?.isTerminal == false) {
                result[basisId] = scaleOut
            }
        }
        activeScaleOutsById.forEach { (scaleOutId, scaleOut) ->
            if (scaleOut.strategyId == strategyId && remainingScaleOutExitIds[scaleOutId].orEmpty().isNotEmpty()) {
                result[scaleOutId] = scaleOut
            }
        }
        for ((entryId, bracket) in preFillBrackets) {
            if (bracket.strategyId == strategyId && orders[entryId]?.state?.isTerminal != true) {
                result[entryId] = bracket
            }
        }
        for ((id, managed) in orders) {
            val bracket = managed.request as? OrderRequest.Bracket ?: continue
            if (bracket.strategyId != strategyId || managed.state.isTerminal) continue
            if (id in preFillBrackets || bracket in preFillBrackets.values) continue
            result[id] = bracket
        }
        return result
    }

    private fun overlayPendingOtos(pendingByStrategy: MutableMap<String, MutableMap<String, OrderRequest>>) {
        for ((parentId, oto) in pendingOtosByParent) {
            val strategyId = oto.strategyId
            if (strategyId.isBlank() || orders[parentId]?.state?.isTerminal != false) continue
            // Replace the atomic parent snapshot with the wrapper so restart can re-arm children.
            pendingByStrategy.getOrPut(strategyId) { mutableMapOf() }[parentId] = oto
        }
    }

    private fun overlayPendingScaleOuts(pendingByStrategy: MutableMap<String, MutableMap<String, OrderRequest>>) {
        for ((basisId, scaleOut) in pendingScaleOutsByBasis) {
            val strategyId = scaleOut.strategyId
            if (strategyId.isBlank() || orders[basisId]?.state?.isTerminal != false) continue
            pendingByStrategy.getOrPut(strategyId) { mutableMapOf() }[basisId] = scaleOut
        }
        for ((scaleOutId, scaleOut) in activeScaleOutsById) {
            val strategyId = scaleOut.strategyId
            if (strategyId.isBlank() || remainingScaleOutExitIds[scaleOutId].orEmpty().isEmpty()) continue
            pendingByStrategy.getOrPut(strategyId) { mutableMapOf() }[scaleOutId] = scaleOut
        }
    }

    private fun unarmedChildIds(): Set<String> =
        pendingChildren.values
            .asSequence()
            .flatten()
            .map { it.id }
            .toSet()

    /** Flushes HWM-only trailing-stop changes at the live heartbeat cadence. */
    fun persistTrailingStateIfDirty() {
        if (!trailingStateDirty) return
        runCatching {
            for ((strategyId, stops) in trailingStopsByStrategy()) {
                persistor.saveTrailingStops(strategyId, stops)
            }
            trailingStateDirty = false
        }
    }

    private fun trailingStopsByStrategy(): Map<String, List<com.qkt.persistence.PersistedTrailingStop>> {
        val result = mutableMapOf<String, MutableList<com.qkt.persistence.PersistedTrailingStop>>()
        for ((id, managed) in orders) {
            val request = managed.request
            if (!hasPersistentDynamicState(request) || managed.state != OrderState.PENDING) continue
            val strategyId = request.strategyId
            if (strategyId.isBlank()) continue
            val entryPrice =
                when (request) {
                    is OrderRequest.ArmedTrailingStop -> request.entryPrice
                    is OrderRequest.SteppedStop -> request.entryPrice
                    is OrderRequest.TimeTighteningStop -> request.entryPrice
                    is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit ->
                        trailingHwm[id] ?: continue
                    else -> error("unreachable")
                }
            result.getOrPut(strategyId) { mutableListOf() }.add(
                com.qkt.persistence.PersistedTrailingStop(
                    clientOrderId = id,
                    brokerOrderId = managed.brokerOrderId,
                    strategyId = strategyId,
                    request = request,
                    armed = armedTrailArmed[id] ?: false,
                    hwm = trailingHwm[id] ?: entryPrice,
                    stepIndex = steppedStopIndex[id] ?: 0,
                    elapsedIntervals = timeTightenIntervals[id] ?: 0L,
                    stopLevel = managedStopLevel[id],
                ),
            )
        }
        return result
    }

    private fun onAccepted(e: BrokerEvent.OrderAccepted) {
        val applied =
            update(e.clientOrderId) {
                if (it.state == OrderState.PENDING) {
                    it.copy(brokerOrderId = e.brokerOrderId ?: it.brokerOrderId, lastUpdatedAt = clock.now())
                } else {
                    it.copy(
                        state = OrderState.WORKING,
                        brokerOrderId = e.brokerOrderId ?: it.brokerOrderId,
                        lastUpdatedAt = clock.now(),
                    )
                }
            }
        if (!applied) return
        log.info(
            "order accepted order_id={} strategy_id={} broker_order_id={}",
            e.clientOrderId,
            e.strategyId,
            e.brokerOrderId,
        )
        advanceOcoOnAccept(e.clientOrderId)
    }

    private fun onRejected(e: BrokerEvent.OrderRejected) {
        haltCancellations.remove(e.clientOrderId)
        preFillBrackets.remove(e.clientOrderId)
        fillAnchoredFallbackBrackets.remove(e.clientOrderId)
        fillAnchoredAttachedBrackets.remove(e.clientOrderId)
        val unarmedChildren = pendingChildren.remove(e.clientOrderId)
        pendingOtosByParent.remove(e.clientOrderId)
        pendingScaleOutsByBasis.remove(e.clientOrderId)
        partialScaleOutPositionTickets.remove(e.clientOrderId)
        val applied =
            update(e.clientOrderId) {
                it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now())
            }
        if (!applied) return
        ocoSiblingCancelStarted.remove(e.clientOrderId)
        ocoCompensations.remove(e.clientOrderId)?.let { compensation ->
            reportProtectionFailure(
                compensation.strategyId,
                "CRITICAL OCO compensation ${e.clientOrderId} failed for position " +
                    "${compensation.positionTicket}: ${e.reason}",
            )
        }
        exposureEntries.remove(e.clientOrderId)
        completeScaleOutExit(e.clientOrderId, OrderState.REJECTED)
        reportBracketByClientOrderId.remove(e.clientOrderId)?.let { bracket ->
            reportBracketByClientOrderId.remove(bracket.id)
            reportBracketByClientOrderId.remove(bracket.entry.id)
            riskByClientOrderId.remove(bracket.id)
            riskByClientOrderId.remove(bracket.entry.id)
            protectionByClientOrderId.remove(bracket.id)
            protectionByClientOrderId.remove(bracket.entry.id)
        }
        unarmedChildren.orEmpty().forEach { cancel(it.id) }
        failOcoOnReject(e.clientOrderId)
    }

    private fun onPartiallyFilled(e: BrokerEvent.OrderPartiallyFilled) {
        val applied =
            update(e.clientOrderId) {
                it.copy(
                    state = OrderState.PARTIALLY_FILLED,
                    cumulativeFilledQuantity = e.cumulativeFilled,
                    avgFillPrice = blendAvg(it.avgFillPrice, it.cumulativeFilledQuantity, e.price, e.quantity),
                    lastUpdatedAt = clock.now(),
                )
            }
        if (!applied) return
        if (e.clientOrderId in pendingScaleOutsByBasis) {
            e.brokerOrderId
                ?.takeIf { it.isNotBlank() }
                ?.let { partialScaleOutPositionTickets[e.clientOrderId] = it }
        }
        exposureEntries[e.clientOrderId]?.filledQuantity = e.cumulativeFilled
        log.info(
            "order partially filled order_id={} strategy_id={} symbol={} side={} qty={} cumulative={} price={}",
            e.clientOrderId,
            e.strategyId,
            e.symbol,
            e.side,
            e.quantity,
            e.cumulativeFilled,
            e.price,
        )
        if (e.quantity.signum() > 0 && e.cumulativeFilled.signum() > 0) {
            resolveOcoOnExecution(e.clientOrderId)
        }
    }

    private fun onFilled(e: BrokerEvent.OrderFilled) {
        haltCancellations.remove(e.clientOrderId)
        if (!e.updatesOrderExecution) {
            log.info(
                "position close observed order_id={} broker_order_id={} — terminal order record unchanged",
                e.clientOrderId,
                e.brokerOrderId,
            )
            completeAttachedBracketOnVenueClose(e)
            return
        }
        preFillBrackets.remove(e.clientOrderId)
        val existing = orders[e.clientOrderId]
        if (existing?.state?.isTerminal == true) {
            log.error(
                "ignoring duplicate fill for terminal order {} in state {} — cumulative execution is immutable",
                e.clientOrderId,
                existing.state,
            )
            return
        }
        val applied =
            update(e.clientOrderId) {
                val newCumulative = it.cumulativeFilledQuantity + e.quantity
                it.copy(
                    state = OrderState.FILLED,
                    brokerOrderId = e.brokerOrderId ?: it.brokerOrderId,
                    cumulativeFilledQuantity = newCumulative,
                    avgFillPrice = blendAvg(it.avgFillPrice, it.cumulativeFilledQuantity, e.price, e.quantity),
                    lastUpdatedAt = clock.now(),
                )
            }
        if (!applied) return
        ocoCompensations.remove(e.clientOrderId)
        exposureEntries.remove(e.clientOrderId)
        completeScaleOutExit(e.clientOrderId, OrderState.FILLED)
        log.info(
            "order filled order_id={} strategy_id={} symbol={} side={} qty={} price={}",
            e.clientOrderId,
            e.strategyId,
            e.symbol,
            e.side,
            e.quantity,
            e.price,
        )
        val filledSibling = filledEmulatedOcoSibling(e.clientOrderId)
        if (filledSibling != null) {
            discardChildrenForCompensatedOcoLeg(e.clientOrderId)
            compensateEmulatedOcoDoubleFill(e, filledSibling)
            clearOcoSequenceFor(e.clientOrderId)
            return
        }
        val pending = pendingChildren.remove(e.clientOrderId)
        pendingOtosByParent.remove(e.clientOrderId)
        val fallbackBracket = fillAnchoredFallbackBrackets.remove(e.clientOrderId)
        val attachedBracket = fillAnchoredAttachedBrackets.remove(e.clientOrderId)
        when {
            fallbackBracket != null -> dispatch(bracketExitOco(fallbackBracket, e.price, e.quantity))
            attachedBracket != null -> {
                val resolved = resolveBracketAtFill(attachedBracket, e.price)
                val sl =
                    when (val spec = resolved.stopLoss) {
                        is StopLossSpec.Fixed -> spec.price
                        is StopLossSpec.ArmedTrail ->
                            if (resolved.side == Side.BUY) {
                                e.price - spec.trailDistance
                            } else {
                                e.price + spec.trailDistance
                            }
                        is StopLossSpec.SteppedStop ->
                            if (resolved.side == Side.BUY) {
                                e.price - spec.initialDistance
                            } else {
                                e.price + spec.initialDistance
                            }
                        is StopLossSpec.TimeTighten ->
                            if (resolved.side == Side.BUY) {
                                e.price - spec.initialDistance
                            } else {
                                e.price + spec.initialDistance
                            }
                    }
                e.brokerOrderId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ticket ->
                        val operationId = "bracket:${e.clientOrderId}:${e.sequenceId}"
                        val fallbackStop =
                            if (resolved.stopLoss is StopLossSpec.Fixed) {
                                OrderRequest.Stop(
                                    id = "${resolved.id}-sl",
                                    symbol = resolved.symbol,
                                    side = if (resolved.side == Side.BUY) Side.SELL else Side.BUY,
                                    quantity = e.quantity,
                                    stopPrice = sl,
                                    timeInForce = resolved.timeInForce,
                                    timestamp = clock.now(),
                                    strategyId = resolved.strategyId,
                                    legIntent = resolved.exitLegIntent(),
                                )
                            } else {
                                null
                            }
                        pendingPositionModifications[operationId] =
                            BracketPositionModification(ticket, resolved.strategyId, fallbackStop)
                        modifyPositionAsync(operationId, ticket, sl, resolved.takeProfit)
                    }
                pending.orEmpty().forEach { child ->
                    val anchored =
                        when (child) {
                            is OrderRequest.ArmedTrailingStop ->
                                child.copy(entryPrice = e.price, quantity = child.quantity.min(e.quantity))
                            is OrderRequest.SteppedStop ->
                                child.copy(
                                    entryPrice = e.price,
                                    quantity = child.quantity.min(e.quantity),
                                    timestamp = clock.now(),
                                )
                            is OrderRequest.TimeTighteningStop ->
                                child.copy(
                                    entryPrice = e.price,
                                    quantity = child.quantity.min(e.quantity),
                                    timestamp = clock.now(),
                                )
                            else -> child
                        }
                    dispatch(anchored)
                }
            }
            else -> pending?.forEach { dispatch(it) }
        }
        partialScaleOutPositionTickets.remove(e.clientOrderId)
        pendingScaleOutsByBasis.remove(e.clientOrderId)?.let { scaleReq ->
            activateScaleOut(
                scaleOut = scaleReq,
                basisQuantity = orders[e.clientOrderId]?.cumulativeFilledQuantity ?: e.quantity,
                positionTicket = e.brokerOrderId?.takeIf { it.isNotBlank() },
            )
        }
        resolveOcoOnExecution(e.clientOrderId)
        ocoSiblingCancelStarted.remove(e.clientOrderId)
        detectExitIncreasedExposure(e)
        retireStaleProtectiveExits(e.strategyId, e.symbol)
    }

    /**
     * Reduce-only tripwire (#1069): an engine-managed protective exit may only shrink the
     * position its bracket opened. After an exit fill the net position must not sit on the
     * fill's own side — long after a BUY exit (or short after a SELL exit) means the "exit"
     * added exposure. The sweep above prevents the known stale-exit path; this detector
     * refuses to let ANY future path fail silently: it raises the operator protection alert
     * (live: telegram/log; backtest: report + log) the moment the invariant breaks.
     */
    private fun detectExitIncreasedExposure(e: BrokerEvent.OrderFilled) {
        if (!e.clientOrderId.endsWith("-sl") && !e.clientOrderId.endsWith("-tp")) return
        if (isLegLinked(e.clientOrderId)) return
        val netQty = strategyNetQty?.invoke(e.strategyId, e.symbol) ?: return
        val landedOnOwnSide =
            (e.side == Side.BUY && netQty.signum() > 0) ||
                (e.side == Side.SELL && netQty.signum() < 0)
        if (!landedOnOwnSide) return
        val message =
            "REDUCE-ONLY VIOLATION: protective exit ${e.clientOrderId} filled ${e.side} " +
                "${e.quantity} ${e.symbol} but net position is now $netQty — an exit added exposure"
        log.error(message)
        reportProtectionFailure(e.strategyId, message)
    }

    /**
     * A protective exit exists to REDUCE the position its bracket opened. When a netting fill
     * consumes that position (reversal, or a flatten), the venue drops the position's SL/TP with
     * it — an engine-managed resting exit must be retired the same way, or it later fires as a
     * naked opposite-direction entry with no protection of its own (#1069). Stale means: the
     * exit's side would INCREASE the current net strategy position (any exit is stale when flat).
     * A partial reduce that keeps the sign leaves exits alone — reducing them is venue-faithful
     * resizing, tracked separately.
     */
    private fun retireStaleProtectiveExits(
        strategyId: String,
        symbol: String,
    ) {
        val netQty = strategyNetQty?.invoke(strategyId, symbol) ?: return
        val staleSide =
            when {
                netQty.signum() > 0 -> Side.BUY
                netQty.signum() < 0 -> Side.SELL
                else -> null // flat: every resting exit is stale
            }
        val stale =
            orders.entries.filter { (id, managed) ->
                !managed.state.isTerminal &&
                    (id.endsWith("-sl") || id.endsWith("-tp")) &&
                    managed.request.strategyId == strategyId &&
                    managed.request.symbol == symbol &&
                    (staleSide == null || managed.request.side == staleSide) &&
                    !isLegLinked(id)
            }
        for ((id, managed) in stale) {
            val request = managed.request
            log.warn(
                "retiring stale protective exit {} {} {} — its position was consumed (net {} {})",
                id,
                request.side,
                request.quantity,
                netQty,
                symbol,
            )
            cancel(id)
        }
    }

    /**
     * A venue-attached bracket has no resting exit orders — the venue closes the ticket when
     * SL/TP is hit and reports it under the entry id. Once the closed quantity covers the fill,
     * the bracket is done: release any engine-held stop armed against the ticket, cancel held
     * children, and mark the wrapper terminal so it stops being persisted and can be reclaimed.
     * A wrapper with a child still live on the venue is left alone; its own terminal event
     * completes it.
     */
    private fun completeAttachedBracketOnVenueClose(e: BrokerEvent.OrderFilled) {
        val entry = orders[e.clientOrderId] ?: return
        if (entry.request !is OrderRequest.Bracket || entry.state != OrderState.FILLED) return
        val filled = entry.cumulativeFilledQuantity.takeIf { it.signum() > 0 } ?: entry.request.quantity
        val closed = (venueClosedQuantityByEntry[entry.id] ?: BigDecimal.ZERO) + e.quantity
        if (closed < filled) {
            venueClosedQuantityByEntry[entry.id] = closed
            return
        }
        venueClosedQuantityByEntry.remove(entry.id)
        val ticket = e.brokerOrderId ?: entry.brokerOrderId
        if (ticket != null) {
            val held = engineHeldCloseTickets.filterValues { it == ticket }.keys
            for (id in held) {
                val managed = orders[id] ?: continue
                if (managed.state == OrderState.PENDING || managed.state == OrderState.CREATED) cancel(id)
            }
        }
        val wrapperId = entry.parentClientOrderId ?: return
        val wrapper = orders[wrapperId] ?: return
        if (wrapper.state.isTerminal) return
        for (childId in wrapper.childClientOrderIds) {
            val child = orders[childId] ?: continue
            if (child.state == OrderState.PENDING || child.state == OrderState.CREATED) cancel(childId)
        }
        val liveChild = wrapper.childClientOrderIds.any { orders[it]?.state?.isTerminal == false }
        if (liveChild) return
        update(wrapperId) { it.copy(state = OrderState.FILLED, lastUpdatedAt = clock.now()) }
        exposureEntries.remove(wrapperId)
    }

    /** Cancel an OCO sibling exactly once, beginning with the first positive execution slice. */
    private fun resolveOcoOnExecution(clientOrderId: String) {
        val siblingIds = siblings[clientOrderId].orEmpty()
        if (siblingIds.isEmpty() || !ocoSiblingCancelStarted.add(clientOrderId)) return
        var deferredSiblingCancel = false
        siblingIds.forEach { sibId ->
            val sib = orders[sibId] ?: return@forEach
            if (sib.state.isTerminal) return@forEach
            // If the sibling is an OCO leg2 that the venue hasn't acknowledged yet, its ticket
            // is unknown — a cancel now would no-op at the venue. Defer it to leg2's acceptance.
            val pending = ocoByLeg2[sibId]
            if (pending != null && pending.leg2Placed && !pending.leg2Confirmed) {
                pending.leg2PendingCancel = true
                deferredSiblingCancel = true
            } else {
                cancel(sibId)
            }
        }
        // The filled leg resolved its OCO; drop the sequence unless a cancel is still deferred
        // (that path clears it once leg2 is acknowledged and cancelled).
        if (!deferredSiblingCancel) clearOcoSequenceFor(clientOrderId)
    }

    private fun filledEmulatedOcoSibling(clientOrderId: String): ManagedOrder? {
        if (clientOrderId !in emulatedOcoGroupByLeg) return null
        return siblings[clientOrderId]
            .orEmpty()
            .asSequence()
            .mapNotNull(orders::get)
            .firstOrNull { it.state == OrderState.FILLED }
    }

    private fun discardChildrenForCompensatedOcoLeg(clientOrderId: String) {
        pendingChildren.remove(clientOrderId)
        pendingOtosByParent.remove(clientOrderId)
        fillAnchoredFallbackBrackets.remove(clientOrderId)
        fillAnchoredAttachedBrackets.remove(clientOrderId)
        pendingScaleOutsByBasis.remove(clientOrderId)
    }

    private fun compensateEmulatedOcoDoubleFill(
        secondFill: BrokerEvent.OrderFilled,
        firstFilledSibling: ManagedOrder,
    ) {
        val strategyId =
            secondFill.strategyId.ifBlank {
                orders[secondFill.clientOrderId]?.request?.strategyId.orEmpty()
            }
        val positionTicket = secondFill.brokerOrderId?.takeIf { it.isNotBlank() }
        val groupId = emulatedOcoGroupByLeg.getValue(secondFill.clientOrderId)
        if (positionTicket == null) {
            reportProtectionFailure(
                strategyId,
                "CRITICAL OCO invariant violated for $groupId: ${firstFilledSibling.id} and " +
                    "${secondFill.clientOrderId} both filled, but the second fill has no owned position ticket; " +
                    "automatic close was refused",
            )
            return
        }

        val compensationId = "$groupId-oco-double-fill-close-${secondFill.clientOrderId}"
        val secondPositionQuantity =
            orders[secondFill.clientOrderId]?.cumulativeFilledQuantity?.takeIf { it.signum() > 0 }
                ?: secondFill.quantity
        reportProtectionFailure(
            strategyId,
            "CRITICAL OCO invariant violated for $groupId: ${firstFilledSibling.id} and " +
                "${secondFill.clientOrderId} both filled; closing second position ticket $positionTicket",
        )
        ocoCompensations[compensationId] = OcoCompensation(strategyId, positionTicket)
        val close =
            OrderRequest.Market(
                id = compensationId,
                symbol = secondFill.symbol,
                side = if (secondFill.side == Side.BUY) Side.SELL else Side.BUY,
                quantity = secondPositionQuantity,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
                strategyId = strategyId,
                closesTicket = positionTicket,
                legIntent = LegIntent.Close(ticket = positionTicket),
            )
        val ack = submit(close)
        if (!ack.accepted) {
            ocoCompensations.remove(compensationId)?.let {
                reportProtectionFailure(
                    strategyId,
                    "CRITICAL OCO compensation $compensationId was rejected for position $positionTicket: " +
                        (ack.rejectReason ?: "unknown reason"),
                )
            }
        }
    }

    private fun onCancelled(e: BrokerEvent.OrderCancelled) {
        haltCancellations.remove(e.clientOrderId)
        preFillBrackets.remove(e.clientOrderId)
        fillAnchoredFallbackBrackets.remove(e.clientOrderId)
        fillAnchoredAttachedBrackets.remove(e.clientOrderId)
        val applied =
            update(e.clientOrderId) {
                it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now())
            }
        if (!applied) return
        ocoSiblingCancelStarted.remove(e.clientOrderId)
        exposureEntries.remove(e.clientOrderId)
        completeScaleOutExit(e.clientOrderId, OrderState.CANCELLED)
        val unarmedChildren = pendingChildren.remove(e.clientOrderId)
        pendingOtosByParent.remove(e.clientOrderId)
        val pendingScaleOut = pendingScaleOutsByBasis.remove(e.clientOrderId)
        val partialPositionTicket = partialScaleOutPositionTickets.remove(e.clientOrderId)
        unarmedChildren?.forEach { child -> cancel(child.id) }
        val cancelled = orders[e.clientOrderId]
        val wrapperId = cancelled?.parentClientOrderId
        val wrapperWasExplicitlyCancelled =
            wrapperId != null &&
                (wrapperId in cancellingScaleOutWrappers || orders[wrapperId]?.state == OrderState.CANCELLED)
        if (pendingScaleOut != null &&
            cancelled != null &&
            cancelled.cumulativeFilledQuantity.signum() > 0 &&
            !wrapperWasExplicitlyCancelled
        ) {
            activateScaleOut(
                scaleOut = pendingScaleOut,
                basisQuantity = cancelled.cumulativeFilledQuantity,
                positionTicket = partialPositionTicket,
            )
        }
        log.info(
            "order cancelled order_id={} strategy_id={} reason={}",
            e.clientOrderId,
            e.strategyId,
            e.reason,
        )
        clearOcoSequenceFor(e.clientOrderId)
    }

    private fun activateScaleOut(
        scaleOut: OrderRequest.ScaleOut,
        basisQuantity: BigDecimal,
        positionTicket: String?,
    ) {
        if (requireArmedTrailTicket &&
            OrderTypeCapability.MULTI_POSITION_PER_SYMBOL in broker.capabilitiesFor(scaleOut.symbol) &&
            positionTicket == null
        ) {
            reportProtectionFailure(
                scaleOut.strategyId,
                "ScaleOut ${scaleOut.id} basis ${scaleOut.basis.id} completed without an owned position ticket; " +
                    "no opposite exit orders were armed",
            )
            update(scaleOut.id) { it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now()) }
            return
        }
        val exitSide = if (scaleOut.side == Side.BUY) Side.SELL else Side.BUY
        val exitRequests =
            scaleOut.legs.mapIndexed { idx, leg ->
                val legQuantity =
                    basisQuantity
                        .multiply(leg.fraction)
                        .setScale(Money.SCALE, Money.ROUNDING)
                OrderRequest.IfTouched(
                    id = "${scaleOut.id}-leg-$idx",
                    symbol = scaleOut.symbol,
                    side = exitSide,
                    quantity = legQuantity,
                    triggerPrice = leg.priceTarget,
                    onTrigger = TriggerType.MARKET,
                    timeInForce = scaleOut.timeInForce,
                    timestamp = clock.now(),
                    strategyId = scaleOut.strategyId,
                    closesTicket = positionTicket,
                    partialClose = legQuantity < basisQuantity,
                    legIntent = LegIntent.Close(ticket = positionTicket, partial = legQuantity < basisQuantity),
                )
            }
        armScaleOutExits(scaleOut, exitRequests)
    }

    private fun armScaleOutExits(
        scaleOut: OrderRequest.ScaleOut,
        exits: List<OrderRequest.IfTouched>,
    ) {
        val now = clock.now()
        val exitIds = exits.mapTo(linkedSetOf()) { it.id }
        activeScaleOutsById[scaleOut.id] = scaleOut
        remainingScaleOutExitIds[scaleOut.id] = exitIds
        for (exit in exits) {
            scaleOutByExitId[exit.id] = scaleOut.id
            val managed =
                ManagedOrder(
                    id = exit.id,
                    request = exit,
                    state = OrderState.PENDING,
                    parentClientOrderId = scaleOut.id,
                    createdAt = now,
                    lastUpdatedAt = now,
                )
            orders[exit.id] = managed
            indexLive(managed)
            registerExposure(exit)
        }
        orders[scaleOut.id]?.let { wrapper ->
            orders[scaleOut.id] =
                wrapper.copy(
                    childClientOrderIds = listOf(scaleOut.basis.id) + exitIds,
                    lastUpdatedAt = now,
                )
        }
        persistSubmissionIntent(scaleOut.strategyId)
        for (exit in exits) {
            bus.publish(
                BrokerEvent.OrderAccepted(
                    clientOrderId = exit.id,
                    brokerOrderId = exit.id,
                    strategyId = exit.strategyId,
                    timestamp = now,
                ),
            )
        }
    }

    private fun completeScaleOutExit(
        exitId: String,
        terminalState: OrderState,
    ) {
        val scaleOutId = scaleOutByExitId.remove(exitId) ?: return
        val remaining = remainingScaleOutExitIds[scaleOutId] ?: return
        remaining.remove(exitId)
        if (remaining.isNotEmpty()) {
            persistAll()
            return
        }
        remainingScaleOutExitIds.remove(scaleOutId)
        activeScaleOutsById.remove(scaleOutId)
        update(scaleOutId) { it.copy(state = terminalState, lastUpdatedAt = clock.now()) }
    }

    private fun evaluateTriggers(tick: Tick) {
        lastObservedPrice[tick.symbol] = tick.price
        // Only this symbol's live orders drive trailing + trigger evaluation — O(this symbol),
        // not O(all live). An id in the index with no entry in [orders] is an invariant violation,
        // not an expected absence, so surface it.
        symbolLiveScratch.clear()
        liveBySymbol[tick.symbol]?.let { ids ->
            for (id in ids) {
                symbolLiveScratch.add(orders[id] ?: error("live order index desync: $id"))
            }
        }
        for (i in symbolLiveScratch.indices) {
            val managed = symbolLiveScratch[i]
            if (managed.state != OrderState.PENDING) continue
            if (isPersistentManagedStop(managed.request) &&
                requireArmedTrailTicket &&
                managedStopCloseTicket(managed.request) == null
            ) {
                log.warn(
                    "cancelling engine-managed stop {} because its venue position ticket no longer exists",
                    managed.id,
                )
                cancel(managed.id)
                continue
            }
            updateTrailingHwm(managed, tick.price)
        }

        // Phase 38: sweep pending GTD orders past their deadline when the broker doesn't
        // self-cancel. Only runs when the venue can't self-expire — MT5 returns
        // supportsNativeGtd=true and skips it; PaperBroker, Bybit, and LogBroker fall through here.
        // Walks [gtdLive] (deadline-bearing orders only) and compares longs; the live order is
        // resolved only for the few that actually expired, in the same order a full scan would cancel.
        // One timestamp per pass: GTD, time-exit, and stack deadlines all compare against the same
        // tick instant. The empty guards keep the pass iterator-free when nothing has a deadline.
        val now = clock.now()
        if (!broker.supportsNativeGtd && gtdLive.isNotEmpty()) {
            gtdExpiredScratch.clear()
            for ((id, deadline) in gtdLive) {
                if (now >= deadline) gtdExpiredScratch.add(id)
            }
            for (i in gtdExpiredScratch.indices) {
                val managed = orders[gtdExpiredScratch[i]] ?: continue
                if (managed.state.isTerminal) continue
                if (managed.state != OrderState.PENDING && managed.state != OrderState.WORKING) continue
                cancel(managed.id)
            }
        }

        if (timeExits.isNotEmpty()) {
            expiredExitsScratch.clear()
            for (te in timeExits.values) {
                if (now >= te.deadline.toEpochMilli()) expiredExitsScratch.add(te)
            }
            for (i in expiredExitsScratch.indices) {
                val te = expiredExitsScratch[i]
                timeExits.remove(te.id)
                handleTimeExitExpiry(te)
            }
        }

        val activeStacks = stacks.activeView()
        if (activeStacks.isNotEmpty()) {
            expiredStacksScratch.clear()
            for (state in activeStacks) {
                val deadline = state.deadlineEpochMs ?: continue
                if (now < deadline) continue
                expiredStacksScratch.add(state)
            }
            for (i in expiredStacksScratch.indices) {
                val state = expiredStacksScratch[i]
                cancelStackPending(state.id)
                stacks.terminate(state.id)
            }
        }

        triggeredScratch.clear()
        for (i in symbolLiveScratch.indices) {
            val managed = symbolLiveScratch[i]
            if (managed.state == OrderState.PENDING && triggerHit(managed, tick)) {
                triggeredScratch.add(managed)
            }
        }
        for (i in triggeredScratch.indices) {
            fireFallbackTrigger(triggeredScratch[i], tick.price)
        }

        runGc()
    }

    private fun handleTimeExitExpiry(te: OrderRequest.TimeExit) {
        val target = orders[te.target.id] ?: return
        when (te.onExpiry) {
            ExpiryAction.CANCEL -> {
                if (!target.state.isTerminal) cancel(te.target.id)
                update(te.id) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            }
            ExpiryAction.CLOSE_AT_MARKET -> {
                if (target.state == OrderState.FILLED) {
                    val exitSide = if (te.target.side == Side.BUY) Side.SELL else Side.BUY
                    val closing =
                        OrderRequest.Market(
                            id = "${te.id}-close",
                            symbol = te.symbol,
                            side = exitSide,
                            quantity = te.target.quantity,
                            timeInForce = te.timeInForce,
                            timestamp = clock.now(),
                            strategyId = te.strategyId,
                            legIntent = te.target.exitLegIntent(),
                        )
                    submit(closing)
                } else if (!target.state.isTerminal) {
                    cancel(te.target.id)
                }
                update(te.id) { it.copy(state = OrderState.FILLED, lastUpdatedAt = clock.now()) }
            }
        }
    }

    private fun updateTrailingHwm(
        managed: ManagedOrder,
        tickPrice: BigDecimal,
    ) {
        when (val request = managed.request) {
            is OrderRequest.ArmedTrailingStop -> {
                // The "favorable side" for an ArmedTrailingStop (an EXIT order) is the
                // direction the underlying entry is profiting in. Exit side BUY (i.e.
                // entry was SELL) → favorable means price falling, hwm tracks the low.
                // Exit side SELL (entry was BUY) → favorable means price rising, hwm
                // tracks the high.
                val current = trailingHwm[managed.id] ?: request.entryPrice
                val newHwm =
                    when (request.side) {
                        Side.SELL -> if (tickPrice > current) tickPrice else current
                        Side.BUY -> if (tickPrice < current) tickPrice else current
                    }
                if (newHwm != current) {
                    trailingHwm[managed.id] = newHwm
                    trailingStateDirty = true
                }

                // Arming gate: MFE = |hwm - entry|. Once MFE crosses the threshold, arm
                // for life. Subsequent thresholds being un-crossed do NOT disarm.
                if (armedTrailArmed[managed.id] == false) {
                    val mfe = newHwm.subtract(request.entryPrice).abs()
                    if (mfe.compareTo(request.mfeThreshold) >= 0) {
                        armedTrailArmed[managed.id] = true
                        log.info(
                            "armed-trail armed: order_id={} symbol={} entry={} hwm={} mfe={} threshold={}",
                            managed.id,
                            managed.request.symbol,
                            request.entryPrice,
                            newHwm,
                            mfe,
                            request.mfeThreshold,
                        )
                        // Persist the one-time arm transition immediately so a crash right after
                        // arming still resumes armed on restart, not reset to the entry (#436).
                        persistAll()
                    }
                }
            }
            is OrderRequest.SteppedStop -> {
                val currentHwm = trailingHwm[managed.id] ?: request.entryPrice
                val newHwm =
                    when (request.side) {
                        Side.SELL -> if (tickPrice > currentHwm) tickPrice else currentHwm
                        Side.BUY -> if (tickPrice < currentHwm) tickPrice else currentHwm
                    }
                if (newHwm != currentHwm) {
                    trailingHwm[managed.id] = newHwm
                    trailingStateDirty = true
                }
                val mfe = newHwm.subtract(request.entryPrice).abs()
                var index = steppedStopIndex[managed.id] ?: 0
                var level =
                    managedStopLevel[managed.id]
                        ?: initialStopLevel(request.side, request.entryPrice, request.initialDistance)
                var advanced = false
                var tightened = false
                while (index < request.steps.size && mfe >= request.steps[index].mfeThreshold) {
                    val step = request.steps[index]
                    val candidate = profitStopLevel(request.side, request.entryPrice, step.profitDistance)
                    if (isTighter(request.side, candidate, level)) {
                        level = candidate
                        managedStopLevel[managed.id] = candidate
                        tightened = true
                        log.info(
                            "stepped stop advanced: order_id={} symbol={} step={} mfe={} stop={}",
                            managed.id,
                            request.symbol,
                            index + 1,
                            mfe,
                            candidate,
                        )
                    } else {
                        log.warn(
                            "stepped stop skipped widening target: order_id={} symbol={} step={} current={} candidate={}",
                            managed.id,
                            request.symbol,
                            index + 1,
                            level,
                            candidate,
                        )
                    }
                    index++
                    advanced = true
                }
                if (advanced) {
                    steppedStopIndex[managed.id] = index
                    trailingStateDirty = true
                    persistAll()
                    if (tightened) modifyManagedStopAtVenue(managed, level, "step-$index")
                }
            }
            is OrderRequest.TimeTighteningStop -> {
                val floorLevel = initialStopLevel(request.side, request.entryPrice, request.floorDistance)
                if (managedStopLevel[managed.id]?.compareTo(floorLevel) == 0) return
                val elapsedMs = (clock.now() - request.timestamp).coerceAtLeast(0L)
                val intervals = elapsedMs / request.intervalMs
                val prior = timeTightenIntervals[managed.id] ?: 0L
                if (intervals > prior) {
                    val reduction = request.tightenBy.multiply(BigDecimal.valueOf(intervals), Money.CONTEXT)
                    val distance = request.initialDistance.subtract(reduction, Money.CONTEXT).max(request.floorDistance)
                    val current =
                        managedStopLevel[managed.id]
                            ?: initialStopLevel(request.side, request.entryPrice, request.initialDistance)
                    val candidate = initialStopLevel(request.side, request.entryPrice, distance)
                    timeTightenIntervals[managed.id] = intervals
                    val tightened = isTighter(request.side, candidate, current)
                    if (tightened) {
                        managedStopLevel[managed.id] = candidate
                        log.info(
                            "time-tightening stop advanced: order_id={} symbol={} intervals={} distance={} stop={}",
                            managed.id,
                            request.symbol,
                            intervals,
                            distance,
                            candidate,
                        )
                    }
                    trailingStateDirty = true
                    persistAll()
                    if (tightened) {
                        modifyManagedStopAtVenue(managed, candidate, "interval-$intervals")
                    }
                }
            }
            else -> {
                val params = trailParams(request) ?: return
                val current = trailingHwm[managed.id]
                val newHwm =
                    when (params.side) {
                        Side.SELL -> if (current == null || tickPrice > current) tickPrice else current
                        Side.BUY -> if (current == null || tickPrice < current) tickPrice else current
                    }
                if (newHwm != current) {
                    trailingHwm[managed.id] = newHwm
                    trailingStateDirty = true
                }
            }
        }
    }

    private fun trailParams(request: OrderRequest): TrailParams? =
        when (request) {
            is OrderRequest.TrailingStop ->
                TrailParams(request.side, request.trailAmount, request.trailMode, limitOffset = null)
            is OrderRequest.TrailingStopLimit ->
                TrailParams(request.side, request.trailAmount, request.trailMode, limitOffset = request.limitOffset)
            else -> null
        }

    private fun trailLevel(managed: ManagedOrder): BigDecimal? {
        when (val request = managed.request) {
            is OrderRequest.ArmedTrailingStop -> {
                val isArmed = armedTrailArmed[managed.id] == true
                val reference =
                    if (isArmed) {
                        trailingHwm[managed.id] ?: return null
                    } else {
                        request.entryPrice
                    }
                // Exit-side SELL closes a long → stop sits BELOW reference (`hwm` or
                // `entry`), fires on a drop. Exit-side BUY closes a short → stop ABOVE.
                return when (request.side) {
                    Side.SELL -> reference - request.trailDistance
                    Side.BUY -> reference + request.trailDistance
                }
            }
            is OrderRequest.SteppedStop, is OrderRequest.TimeTighteningStop ->
                return managedStopLevel[managed.id]
            else -> {
                val params = trailParams(request) ?: return null
                val hwm = trailingHwm[managed.id] ?: return null
                return when (params.trailMode) {
                    TrailMode.ABSOLUTE ->
                        if (params.side == Side.SELL) hwm - params.trailAmount else hwm + params.trailAmount
                    TrailMode.PERCENT -> {
                        val factor = params.trailAmount.divide(BigDecimal("100"), Money.CONTEXT)
                        if (params.side == Side.SELL) {
                            hwm
                                .multiply(BigDecimal.ONE - factor, Money.CONTEXT)
                                .setScale(Money.SCALE, Money.ROUNDING)
                        } else {
                            hwm
                                .multiply(BigDecimal.ONE + factor, Money.CONTEXT)
                                .setScale(Money.SCALE, Money.ROUNDING)
                        }
                    }
                }
            }
        }
    }

    // Side-aware like the venue: a BUY executes at the ask, a SELL at the bid, so the
    // engine-held trigger compares against the side's execution price — otherwise
    // engine-held triggers fire on mid while native venue triggers fire on bid/ask,
    // and live is internally inconsistent (#382).
    private fun triggerHit(
        managed: ManagedOrder,
        tick: com.qkt.marketdata.Tick,
    ): Boolean {
        val request = managed.request
        val exec = if (request.side == Side.BUY) tick.buyExecPrice() else tick.sellExecPrice()
        return when (request) {
            is OrderRequest.Stop ->
                if (request.side == Side.BUY) exec >= request.stopPrice else exec <= request.stopPrice
            is OrderRequest.StopLimit ->
                if (request.side == Side.BUY) exec >= request.stopPrice else exec <= request.stopPrice
            is OrderRequest.IfTouched ->
                if (request.side == Side.BUY) exec <= request.triggerPrice else exec >= request.triggerPrice
            is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit -> {
                val params = trailParams(request) ?: return false
                val level = trailLevel(managed) ?: return false
                if (params.side == Side.SELL) exec <= level else exec >= level
            }
            is OrderRequest.ArmedTrailingStop -> {
                val level = trailLevel(managed) ?: return false
                // Exit SELL fires when price falls to the stop. Exit BUY fires when
                // price rises to the stop. Matches [OrderRequest.TrailingStop] semantics.
                if (request.side == Side.SELL) exec <= level else exec >= level
            }
            is OrderRequest.SteppedStop, is OrderRequest.TimeTighteningStop -> {
                val level = trailLevel(managed) ?: return false
                if (request.side == Side.SELL) exec <= level else exec >= level
            }
            else -> false
        }
    }

    private data class TrailParams(
        val side: Side,
        val trailAmount: BigDecimal,
        val trailMode: TrailMode,
        val limitOffset: BigDecimal?,
    )

    private fun fireFallbackTrigger(
        managed: ManagedOrder,
        tickPrice: BigDecimal,
    ) {
        // [triggeredScratch] is a snapshot. An earlier synchronous fill can cancel this order
        // before its turn in the loop; terminal-state protection rejects the state transition,
        // but without this guard the stale snapshot would still be submitted to the broker.
        if (orders[managed.id]?.state != OrderState.PENDING) return
        val stackOwner = stacks.stackOwning(managed.id)
        if (stackOwner != null) {
            val layerIdx = managed.id.substringAfterLast("-l").toIntOrNull() ?: 0
            log.info(
                "stack fire stack_id={} strategy_id={} layer={} qty={} trigger_price={}",
                stackOwner,
                managed.request.strategyId,
                layerIdx,
                managed.request.quantity,
                tickPrice,
            )
        }
        val internal: OrderRequest =
            when (val req = managed.request) {
                is OrderRequest.Stop -> {
                    val ticket = engineHeldCloseTickets[req.id]
                    OrderRequest.Market(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        closesTicket = ticket,
                        legIntent = req.legIntent.withCloseTicket(ticket),
                    )
                }
                is OrderRequest.StopLimit ->
                    OrderRequest.Limit(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        limitPrice = req.limitPrice,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        legIntent = req.legIntent,
                    )
                is OrderRequest.IfTouched ->
                    if (req.onTrigger == TriggerType.MARKET) {
                        OrderRequest.Market(
                            id = req.id,
                            symbol = req.symbol,
                            side = req.side,
                            quantity = req.quantity,
                            timeInForce = req.timeInForce,
                            timestamp = clock.now(),
                            strategyId = req.strategyId,
                            closesTicket = req.closesTicket,
                            partialClose = req.partialClose,
                            legIntent = req.legIntent,
                        )
                    } else {
                        OrderRequest.Limit(
                            id = req.id,
                            symbol = req.symbol,
                            side = req.side,
                            quantity = req.quantity,
                            limitPrice = req.limitPrice!!,
                            timeInForce = req.timeInForce,
                            timestamp = clock.now(),
                            strategyId = req.strategyId,
                            legIntent = req.legIntent,
                        )
                    }
                is OrderRequest.TrailingStop ->
                    OrderRequest.Market(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        legIntent = req.legIntent,
                    )
                is OrderRequest.ArmedTrailingStop -> {
                    // Close the exact venue position by ticket when this exit belongs to an
                    // independent leg (hedging) — otherwise a plain market opens a counter.
                    val ticket = managedStopCloseTicket(req)
                    OrderRequest.Market(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        closesTicket = ticket,
                        legIntent = req.legIntent.withCloseTicket(ticket),
                    )
                }
                is OrderRequest.SteppedStop, is OrderRequest.TimeTighteningStop -> {
                    val ticket = managedStopCloseTicket(req)
                    OrderRequest.Market(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        closesTicket = ticket,
                        legIntent = req.legIntent.withCloseTicket(ticket),
                    )
                }
                is OrderRequest.TrailingStopLimit -> {
                    val level = trailLevel(managed) ?: error("TrailingStopLimit level missing for ${managed.id}")
                    val limitPrice =
                        if (req.side == Side.SELL) level - req.limitOffset else level + req.limitOffset
                    OrderRequest.Limit(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        limitPrice = limitPrice.setScale(Money.SCALE, Money.ROUNDING),
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        strategyId = req.strategyId,
                        legIntent = req.legIntent,
                    )
                }
                else -> error("Not a Tier 2 fallback type: ${req::class.simpleName}")
            }
        val blockReason = engineHeldSubmissionBlockReason(internal)
        if (blockReason != null) {
            rejectEngineHeld(internal, blockReason)
            return
        }
        engineHeldCloseTickets.remove(managed.id)
        update(managed.id) { it.copy(state = OrderState.SUBMITTED, lastUpdatedAt = clock.now()) }
        persistSubmissionIntent(internal.strategyId)
        broker.submit(internal)
    }

    private fun rejectEngineHeld(
        request: OrderRequest,
        reason: String,
    ) {
        update(request.id) { it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now()) }
        exposureEntries.remove(request.id)
        log.warn("engine-held order {} blocked before broker submission: {}", request.id, reason)
        bus.publish(com.qkt.events.RiskRejectedEvent(request, reason, timestamp = clock.now()))
    }

    private companion object {
        const val HALT_CANCEL_RETRY_MS = 1_000L
        const val HALT_CANCEL_MAX_RETRY_MS = 30_000L
        const val HALT_CANCEL_ALERT_ATTEMPTS = 3
        const val PENDING_SLOT = "pending-orders"
        const val PAIRS_SLOT = "bracket-pairs"
        const val OCO_SLOT = "oco-legs"
        const val TRAILING_SLOT = "trailing-stops"
    }

    /**
     * An exit carrying a [LegIntent.Close] closes exactly its own leg, so the net-based stale
     * sweep and reduce-only tripwire must not judge it: under a hedging book a short leg's BUY
     * stop while net-long is a legitimate exit (#1071).
     */
    private fun isLegLinked(clientOrderId: String): Boolean =
        orders[clientOrderId]?.request?.legIntent is LegIntent.Close

    private fun managedStopCloseTicket(request: OrderRequest): String? =
        closeTicketFor?.invoke(request.strategyId, request.id)
            ?: closePrimaryTicketFor?.invoke(request.strategyId, request.symbol)

    private fun isPersistentManagedStop(request: OrderRequest): Boolean =
        request is OrderRequest.ArmedTrailingStop ||
            request is OrderRequest.SteppedStop ||
            request is OrderRequest.TimeTighteningStop

    private fun hasPersistentDynamicState(request: OrderRequest): Boolean =
        request is OrderRequest.TrailingStop ||
            request is OrderRequest.TrailingStopLimit ||
            isPersistentManagedStop(request)

    private fun isEngineHeldOnRestore(request: OrderRequest): Boolean =
        when (request) {
            is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit -> true
            is OrderRequest.StopLimit ->
                OrderTypeCapability.STOP_LIMIT !in broker.capabilitiesFor(request.symbol)
            else -> isPersistentManagedStop(request)
        }

    private fun initialStopLevel(
        exitSide: Side,
        entryPrice: BigDecimal,
        distance: BigDecimal,
    ): BigDecimal =
        if (exitSide == Side.SELL) {
            entryPrice.subtract(distance, Money.CONTEXT)
        } else {
            entryPrice.add(distance, Money.CONTEXT)
        }

    private fun profitStopLevel(
        exitSide: Side,
        entryPrice: BigDecimal,
        profitDistance: BigDecimal,
    ): BigDecimal =
        if (exitSide == Side.SELL) {
            entryPrice.add(profitDistance, Money.CONTEXT)
        } else {
            entryPrice.subtract(profitDistance, Money.CONTEXT)
        }

    private fun isTighter(
        exitSide: Side,
        candidate: BigDecimal,
        current: BigDecimal,
    ): Boolean = if (exitSide == Side.SELL) candidate > current else candidate < current

    private fun modifyManagedStopAtVenue(
        managed: ManagedOrder,
        stopLoss: BigDecimal,
        transition: String,
    ) {
        if (OrderTypeCapability.POSITION_MODIFY !in broker.capabilitiesFor(managed.request.symbol)) return
        val ticket = managedStopCloseTicket(managed.request) ?: return
        val operationId = "ratchet:${managed.id}:$transition"
        pendingPositionModifications[operationId] =
            RatchetPositionModification(
                orderId = managed.id,
                ticket = ticket,
                strategyId = managed.request.strategyId,
                stopLoss = stopLoss,
            )
        modifyPositionAsync(operationId, ticket, stopLoss, null)
    }

    private fun blendAvg(
        oldAvg: BigDecimal?,
        oldQty: BigDecimal,
        newPrice: BigDecimal,
        newQty: BigDecimal,
    ): BigDecimal {
        if (oldAvg == null || oldQty.signum() == 0) return newPrice
        val totalQty = oldQty + newQty
        return (oldAvg * oldQty + newPrice * newQty)
            .divide(totalQty, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }

    fun pendingStackLayerInfos(): List<PendingStackLayerInfo> =
        stacks.all().flatMap { state ->
            state.pendingLayerIds.mapNotNull { layerId ->
                val managed = orders[layerId] ?: return@mapNotNull null
                if (managed.state != OrderState.PENDING) return@mapNotNull null
                val triggerPrice =
                    when (val r = managed.request) {
                        is OrderRequest.Stop -> r.stopPrice
                        is OrderRequest.Limit -> r.limitPrice
                        else -> return@mapNotNull null
                    }
                val layerIdx = layerId.substringAfterLast("-l").toIntOrNull() ?: 0
                PendingStackLayerInfo(
                    stackId = state.id,
                    layer = layerIdx,
                    triggerPrice = triggerPrice,
                    side = managed.request.side.name,
                    quantity = managed.request.quantity,
                )
            }
        }

    data class PendingStackLayerInfo(
        val stackId: String,
        val layer: Int,
        val triggerPrice: BigDecimal,
        val side: String,
        val quantity: BigDecimal,
    )
}
