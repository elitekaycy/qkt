package com.qkt.app

import com.qkt.app.order.BracketBook
import com.qkt.app.order.BracketRiskRecorder
import com.qkt.app.order.EntryRiskReport
import com.qkt.app.order.HaltCancellations
import com.qkt.app.order.ManagedStopBook
import com.qkt.app.order.ManagedStopTicker
import com.qkt.app.order.OcoExecutionGuard
import com.qkt.app.order.OcoSequencer
import com.qkt.app.order.OrderBook
import com.qkt.app.order.OrderOps
import com.qkt.app.order.OrderStateSnapshots
import com.qkt.app.order.PendingChildBook
import com.qkt.app.order.PendingExposureBook
import com.qkt.app.order.ProtectionLevels
import com.qkt.app.order.ScaleOutBook
import com.qkt.app.order.ScaleOutExits
import com.qkt.app.order.ScaleOutRecovery
import com.qkt.app.order.ScaleOutTracker
import com.qkt.app.order.SiblingCancellation
import com.qkt.app.order.SiblingLinks
import com.qkt.app.order.VenuePositionProtection
import com.qkt.app.order.blendAvg
import com.qkt.app.order.computeChildPrice
import com.qkt.app.order.evaluateAt
import com.qkt.app.order.exposureEntryRequest
import com.qkt.app.order.hasPersistentDynamicState
import com.qkt.app.order.isPersistentManagedStop
import com.qkt.app.order.isTriggered
import com.qkt.app.order.limitReached
import com.qkt.app.order.referencesStackEntryRef
import com.qkt.app.order.resolveBracketAtFill
import com.qkt.app.order.stopReached
import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
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
     * Record per-bracket risk for the backtest report to read via
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

    private val book =
        OrderBook(
            isReferenced = { id -> isReferenced(id) },
            reclaim = { id -> reclaim(id) },
        )
    private val exposure = PendingExposureBook(book)
    private val risk = BracketRiskRecorder(trackRisk, instruments)
    private val haltCancels =
        HaltCancellations(book, broker, clock) { strategyId, message ->
            reportProtectionFailure(strategyId, message)
        }

    // Reusable per-tick scratch buffers for [evaluateTriggers]. Each is cleared and refilled every
    // tick; ArrayList.clear() retains capacity, so steady-state per-tick list allocation is zero.
    // Shareable only because evaluateTriggers runs on the single engine thread and is not reentrant
    // (its sole caller is the TickEvent subscription, and TickEvent is feed-sourced).
    private val symbolLiveScratch = ArrayList<ManagedOrder>()
    private val triggeredScratch = ArrayList<ManagedOrder>()
    private val expiredExitsScratch = ArrayList<OrderRequest.TimeExit>()
    private val gtdExpiredScratch = ArrayList<String>()
    private val expiredStacksScratch = ArrayList<StackTracker.ActiveStack>()

    private val stops = ManagedStopBook()
    private val stopTicker =
        ManagedStopTicker(
            stops = stops,
            clock = clock,
            persist = { persistAll() },
            tightenAtVenue = { managed, level, transition -> venueProtection.ratchet(managed, level, transition) },
        )

    private val lastObservedPrice: MutableMap<String, BigDecimal> = mutableMapOf()

    private val siblings = SiblingLinks()
    private val ops =
        object : OrderOps {
            override fun submit(request: OrderRequest): SubmitAck = this@OrderManager.submit(request)

            override fun dispatch(request: OrderRequest): SubmitAck = this@OrderManager.dispatch(request)

            override fun cancel(clientOrderId: String) = this@OrderManager.cancel(clientOrderId)

            override fun track(managed: ManagedOrder) = this@OrderManager.track(managed)

            override fun update(
                id: String,
                change: (ManagedOrder) -> ManagedOrder,
            ): Boolean = this@OrderManager.update(id, change)

            override fun persistAll() = this@OrderManager.persistAll()

            override fun persistSubmissionIntent(strategyId: String) =
                this@OrderManager.persistSubmissionIntent(strategyId)

            override fun reportProtectionFailure(
                strategyId: String,
                message: String,
            ) = this@OrderManager.reportProtectionFailure(strategyId, message)
        }
    private val ocoGuard = OcoExecutionGuard(book, siblings, clock, ops)
    private val ocoSequencer = OcoSequencer(book, exposure, siblings, ocoGuard, clock, ops)
    private val siblingCancels = SiblingCancellation(book, siblings, ocoSequencer, ops)
    private val venueProtection =
        VenuePositionProtection(
            broker = broker,
            bus = bus,
            ops = ops,
            closeTicket = { request -> managedStopCloseTicket(request) },
            armStackFallbackStop = { stackId, layerOrderId, fillPrice, ticket ->
                attachLayerSl(stackId, layerOrderId, fillPrice, engineHeldCloseTicket = ticket)
            },
            armBracketFallbackStop = { stop, ticket -> armFillAnchoredFallbackStop(stop, ticket) },
        )
    private val scaleOuts = ScaleOutBook()
    private val scaleOutExits =
        ScaleOutExits(scaleOuts, book, exposure, broker, bus, clock, ops, requireArmedTrailTicket)
    private val scaleOutTracker = ScaleOutTracker(scaleOuts, scaleOutExits, book, exposure, clock, ops)
    private val scaleOutRecovery = ScaleOutRecovery(scaleOuts, book, exposure, clock)
    private val engineHeldCloseTickets: MutableMap<String, String> = mutableMapOf()

    private val children = PendingChildBook()
    private val brackets = BracketBook()
    private val snapshots =
        OrderStateSnapshots(persistor, book, children, brackets, scaleOutRecovery, siblings, stops)

    private val timeExits: MutableMap<String, OrderRequest.TimeExit> = mutableMapOf()

    private val stacks: StackTracker = StackTracker()

    /**
     * Returns and removes the recorded risk for [clientOrderId]. Designed to be called once per
     * fill — the entry is consumed so the map doesn't grow unbounded over a long-running session.
     */
    fun riskUsdFor(clientOrderId: String): BigDecimal? = risk.riskUsdFor(clientOrderId)

    /** Resolved venue prices attached to an entry fill; consumed once by the backtest report. */
    fun protectionFor(clientOrderId: String): ProtectionLevels? = risk.protectionFor(clientOrderId)

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
    ): EntryRiskReport? = risk.entryRiskForFill(clientOrderId, quantity, fillPrice, symbol)

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> onAccepted(e) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> onRejected(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> onFilled(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> onStackLayerFilled(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> evaluateStackFlat(e) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> onPartiallyFilled(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> onCancelled(e) }
        bus.subscribe<BrokerEvent.OrderCancelFailed> { e -> onCancelFailed(e) }
        bus.subscribe<BrokerEvent.PositionModificationCompleted> { e -> venueProtection.onCompleted(e) }
        bus.subscribe<TickEvent> { e -> evaluateTriggers(e.tick) }
    }

    fun submit(request: OrderRequest): SubmitAck =
        submitPlanned(LegIntentPlanner.plan(request, positionMode(request.symbol)))

    private fun submitPlanned(request: OrderRequest): SubmitAck {
        book[request.id]?.takeIf { !it.state.isTerminal }?.let { existing ->
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
        if (!request.isCompositeShape()) exposure.register(request)
        return dispatch(request)
    }

    /**
     * Entries this strategy has in flight on [side], counted the way [quantityFor] counts
     * exposure: one per group, plus each ungrouped order, and only on the requested side. The side
     * filter is what separates entries from exits — an open position's protective stop and target
     * rest on the opposite side and stay live until the position closes, so counting both sides
     * reported a pending "entry" for every already-filled position and doubled the total.
     */
    override fun orderCountFor(
        side: Side,
        strategyId: String?,
    ): Int = exposure.orderCountFor(side, strategyId)

    override fun symbolsFor(strategyId: String?): Set<String> = exposure.symbolsFor(strategyId)

    override fun quantityFor(
        symbol: String,
        side: Side,
        strategyId: String?,
    ): BigDecimal = exposure.quantityFor(symbol, side, strategyId)

    fun cancel(clientOrderId: String) {
        val managed = book[clientOrderId] ?: return
        if (managed.state.isTerminal) return
        if (managed.request is OrderRequest.Stack) {
            stacks.get(clientOrderId)?.let { state ->
                for (pid in state.pendingLayerIds.toList()) cancel(pid)
            }
            stacks.terminate(clientOrderId)
            update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            exposure.remove(clientOrderId)
            return
        }
        if (managed.childClientOrderIds.isNotEmpty()) {
            val scaleOutCancellation = managed.request is OrderRequest.ScaleOut
            if (scaleOutCancellation) scaleOuts.cancellingWrappers.add(clientOrderId)
            try {
                for (childId in managed.childClientOrderIds) cancel(childId)
                update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
                exposure.remove(clientOrderId)
            } finally {
                if (scaleOutCancellation) scaleOuts.cancellingWrappers.remove(clientOrderId)
            }
            return
        }
        when (managed.state) {
            OrderState.CREATED, OrderState.PENDING -> {
                update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
                exposure.remove(clientOrderId)
                scaleOutExits.completeExit(clientOrderId, OrderState.CANCELLED)
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
                    val managed = book[state.id] ?: return@filter false
                    (managed.request as? OrderRequest.Stack)?.symbol == symbol
                }.map { it.id }
        for (id in stackIds) cancel(id)
        // Cancel any remaining (non-stack) engine-held or venue-resting orders for the symbol
        // that aren't already children of a stack we just cancelled.
        val pending =
            book.orders.values
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
            book.orders.values
                .filter { managed ->
                    (managed.state == OrderState.PENDING || managed.state == OrderState.WORKING) &&
                        (strategyId == null || managed.request.strategyId == strategyId) &&
                        !mustSurviveHalt(managed)
                }.map { it.id }
        for (id in entryIds) {
            haltCancels.begin(id)
            cancel(id)
        }
    }

    private fun mustSurviveHalt(
        managed: ManagedOrder,
        depth: Int = 0,
    ): Boolean {
        if (managed.id in engineHeldCloseTickets) return true
        if (isPersistentManagedStop(managed.request)) return true
        if (isRiskReducingForHalt(managed.request)) return true
        if (managed.childClientOrderIds.any { book[it]?.state == OrderState.FILLED }) return true
        // A wrapper (OTO / OCO / ScaleOut) is cancelled as a whole and the cascade takes every child
        // with it. If any LIVE child is itself a protective exit that must survive, the wrapper
        // must survive too — otherwise a daily halt strips the stops off open positions (observed
        // 2023-12-11 in a BTC replay: the filled bracket's OTO wrapper was not risk-reducing, its
        // entry child had already left the live map, and the cascade cancelled the working stops).
        if (depth >= 4) return false
        return managed.childClientOrderIds.any { childId ->
            val child = book[childId] ?: return@any false
            !child.state.isTerminal && mustSurviveHalt(child, depth + 1)
        }
    }

    /** Retry halt-owned cancellations that have not produced a terminal broker event. */
    fun retryHaltCancellations(nowMs: Long) = haltCancels.retry(nowMs)

    private fun onCancelFailed(event: BrokerEvent.OrderCancelFailed) = haltCancels.onCancelFailed(event.clientOrderId)

    fun getOrder(clientOrderId: String): ManagedOrder? = book[clientOrderId]

    /** Sibling order ids linked to [clientOrderId] — exposed for restart-recovery tests. */
    fun siblingsOf(clientOrderId: String): List<String> = siblings[clientOrderId]

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
            snapshots.remember(sid)
            val dynamicStops =
                persistor
                    .loadTrailingStops(sid)
                    .associateBy { it.clientOrderId }
                    .toMutableMap()
            for (leg in persistor.loadOcoLegs(sid)) {
                if (book.contains(leg.clientOrderId)) continue
                val groupId =
                    (leg.siblingIds + leg.clientOrderId)
                        .sorted()
                        .joinToString(prefix = "restored-oco:", separator = "|")
                ocoGuard.markEmulated(leg.clientOrderId, groupId)
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
                book.put(managed)
                siblings[leg.clientOrderId] = leg.siblingIds
                exposure.register(leg.request, groupId)
                recovered += managed
            }
            val pairs = persistor.loadBracketPairs(sid)
            for (pair in pairs) {
                val exitIds = listOfNotNull(pair.stopLossClientOrderId, pair.takeProfitClientOrderId)
                for (exitId in exitIds) {
                    siblings[exitId] = exitIds.filter { it != exitId }
                }
            }
            val persistedPending = persistor.loadPendingOrders(sid)
            // A pending order whose symbol no venue routes any more (a broker profile removed
            // from the config since it was persisted) can never be quoted, recovered, or
            // filled; keeping it would fail every deploy of this strategy from now on.
            val unroutable = persistedPending.filterValues { !broker.supports(it.symbol) }
            for ((id, request) in unroutable) {
                log.warn(
                    "[restore] dropping pending order {} for {}: no configured venue routes that symbol",
                    id,
                    request.symbol,
                )
            }
            val pendingOrders = persistedPending - unroutable.keys
            if (unroutable.isNotEmpty()) persistor.savePendingOrders(sid, pendingOrders)
            for ((id, request) in pendingOrders) {
                if (request is OrderRequest.ScaleOut && id == request.id) {
                    scaleOutRecovery.restoreActive(request, pendingOrders.keys)
                }
            }
            for ((id, request) in pendingOrders) {
                if (book.contains(id)) continue
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
                    scaleOutRecovery.restorePending(request, recovered)
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
                        parentClientOrderId = scaleOuts.wrapperOf(id),
                        createdAt = now,
                        lastUpdatedAt = now,
                    )
                book.put(managed)
                exposure.register(request)
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
            // A restored attached entry the venue matched to a position the ledger already booked
            // is a filled entry: it must not count as an open entry order (it would block every
            // re-entry once that position closes) nor hold entry exposure on top of the position.
            // The ticket arrives as OrderAccepted — synchronously here on a direct bus, or later
            // on the engine thread in the daemon — so both restore and onAccepted apply the mark.
            for (id in brackets.restoredAttachedEntries.toList()) {
                val ticket = book[id]?.brokerOrderId ?: continue
                markRestoredAttachedEntryFilled(id, ticket)
            }
        }
    }

    private fun markRestoredAttachedEntryFilled(
        id: String,
        ticket: String,
    ) {
        val managed = book[id] ?: return
        if (managed.state != OrderState.WORKING) return
        if (ticket !in bookedVenueTickets(managed.request.strategyId)) return
        update(id) {
            it.copy(
                state = OrderState.FILLED,
                cumulativeFilledQuantity = it.request.quantity,
                lastUpdatedAt = clock.now(),
            )
        }
        exposure.remove(id)
        brackets.restoredAttachedEntries.remove(id)
        log.info(
            "[restore] attached entry {} is backed by booked venue ticket {} — marked filled without republishing",
            id,
            ticket,
        )
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
        require(book[request.id] == null && request.children.none { book[it.id] != null }) {
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
        book.put(wrapper)

        val parent =
            ManagedOrder(
                id = request.parent.id,
                request = request.parent,
                state = OrderState.WORKING,
                parentClientOrderId = request.id,
                createdAt = now,
                lastUpdatedAt = now,
            )
        book.put(parent)
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
            book.put(managed)
        }
        children.hold(parent.id, request.children, request)
        exposure.register(exposureEntryRequest(request.parent))
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
                book.put(managed)
                brackets.restoredAttachedEntries += attached.id
                brackets.preFill[attached.id] = request
                // Expression-anchored exits are built from the fill. So is an engine-managed
                // stop restored before the venue has quoted its symbol: there is no price to
                // anchor it on yet, and failing the deploy here would be retried forever
                // because the quote only starts flowing once the strategy is deployed.
                val anchorAtFill =
                    needsFillAnchor || (isEngineManagedStop && bracketEntryEstimateOrNull(request) == null)
                if (anchorAtFill) brackets.fillAnchoredAttached[attached.id] = request
                val restoredStop = if (anchorAtFill) null else buildAttachedManagedStop(request, now)
                restoredStop?.let { stop ->
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
                    children.hold(attached.id, listOf(stop))
                }
                exposure.register(attached)
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
                book.put(managed)
                exposure.register(request)
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
                book.put(managed)
                brackets.preFill[entry.id] = request
                // A Market entry restored before the venue has quoted its symbol has no price to
                // anchor the exits on; place them from the actual fill instead of failing the
                // whole deploy (which the daemon would retry forever, quote or no quote).
                val entryEstimate = if (needsFillAnchor) null else bracketEntryEstimateOrNull(request)
                if (entryEstimate == null) {
                    brackets.fillAnchoredFallback[entry.id] = request
                } else {
                    children.hold(entry.id, listOf(bracketExitOco(request, entryEstimate, request.quantity)))
                }
                exposure.register(entry)
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
        if (book.contains(clientOrderId)) return
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
        book.put(managed)
        stops.restore(clientOrderId, request, dynamicState)
        exposure.register(request, groupId)
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
        book[clientOrderId]?.request?.let { OrderDetails(it.symbol, it.side, it.quantity) }

    fun activeOrders(): List<ManagedOrder> = book.orders.values.filter { !it.state.isTerminal }

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
        val ids = book.liveIdsFor(symbol) ?: return 0
        var count = 0
        for (id in ids) {
            if (id !in exposure) continue
            val managed = book[id] ?: error("live order index desync: $id")
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
        if (book.gtdDeadlines.isNotEmpty() ||
            timeExits.isNotEmpty() ||
            stacks.activeView().any { it.deadlineEpochMs != null }
        ) {
            return IntrabarFill.ALL_TICKS
        }
        val ids = book.liveIdsFor(symbol) ?: return IntrabarFill.SYNTHETIC
        // Candles aggregate mid prices, while venue triggers use ask for BUY and bid for SELL.
        // Expand the mid range by the largest observed half-spread so a level crossed only by
        // the executable quote still selects real-tick resolution.
        val executableLow = low - maxHalfSpread
        val executableHigh = high + maxHalfSpread
        var fillable = false
        for (id in ids) {
            val m = book[id] ?: continue
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

    fun pendingOrders(): List<ManagedOrder> = book.orders.values.filter { it.state == OrderState.PENDING }

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
                    ocoSequencer.submit(request)
                }

            is OrderRequest.OTO -> submitOto(request)

            is OrderRequest.Bracket -> {
                risk.recordAtSubmit(request, priceProvider.lastPrice(request.symbol) ?: BigDecimal.ZERO)
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

            is OrderRequest.ScaleOut -> scaleOutTracker.submit(request)

            is OrderRequest.TimeExit -> submitTimeExit(request)

            is OrderRequest.Stack -> submitStack(request)

            else -> error("Order type ${request::class.simpleName} dispatch not yet implemented (added later in 7d-b)")
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
        exposure.register(exposureEntryRequest(req.target))
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
        exposure.register(firstReq)
        dispatch(firstReq)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    private fun onStackLayerFilled(e: BrokerEvent.OrderFilled) {
        val filledQuantity = book[e.clientOrderId]?.cumulativeFilledQuantity ?: e.quantity
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
        val parent = (book[stackId]?.request as? OrderRequest.Stack) ?: return
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
        venueProtection.attachStackLayer(
            operationId = operationId,
            stackId = stackId,
            layerOrderId = layerOrderId,
            fillPrice = fillPrice,
            ticket = resolvedTicket,
            strategyId = parent.strategyId,
            stopLoss = slPrice,
            takeProfit = tpPrice,
        )
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
        if (book.contains(stop.id)) return
        val now = clock.now()
        val managed =
            ManagedOrder(
                id = stop.id,
                request = stop,
                state = OrderState.PENDING,
                createdAt = now,
                lastUpdatedAt = now,
            )
        book.put(managed)
        engineHeldCloseTickets[stop.id] = ticket
        exposure.register(stop)
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
        val parent = (book[stackId]?.request as? OrderRequest.Stack) ?: return null
        val exitSide = if (parent.side == Side.BUY) Side.SELL else Side.BUY
        val slPrice = computeChildPrice(slAst, parent.side, fillPrice, isStopLoss = true)
        val layerEntry = book[layerOrderId] ?: return null
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
        val parent = (book[stackId]?.request as? OrderRequest.Stack) ?: return false
        val tpPrice = computeChildPrice(tpAst, parent.side, fillPrice, isStopLoss = false, slDistance = slDistance)
        val tpId = "$layerOrderId-tp"
        val exitSide = if (parent.side == Side.BUY) Side.SELL else Side.BUY
        val layerEntry = book[layerOrderId] ?: return false
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

    private fun evaluateStackFlat(e: BrokerEvent.OrderFilled) {
        val managed = book[e.clientOrderId] ?: return
        val parentId = managed.parentClientOrderId ?: return
        val parent = book[parentId] ?: return
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
            (book[stackId]?.request as? OrderRequest.Stack)
                ?: error("Stack request not tracked for $stackId")
        for (layer in state.plan.layers.drop(1)) {
            val triggerPrice = resolveTriggerPrice(layer.trigger, anchor)
            val layerOrderId = "$stackId-l${layer.index}"
            val qty = resolveLayerQuantity(layer)
            val pending = buildLayerOrder(layerOrderId, parent, layer, qty, triggerPrice, anchor)
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
            exposure.register(pending)
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

    /** Active protective orders that require ticks on the engine thread to trigger. */
    fun engineHeldProtectiveStopCount(): Int =
        book.orders.values.count { managed ->
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

    /**
     * Turn one layer into the venue order that fires it. A layer written as a plain touch
     * (`AT price`, market on touch) becomes a stop when its trigger sits beyond the seed in the
     * trade direction — price has to move through it — and a limit when the trigger sits behind
     * the seed, where price has to come back to it. A buy stop below the market would be
     * triggered the moment it was placed, which is not what "buy more when down 200" means.
     * The compact `STACK n SPACING d BELOW` form already resolves this at compile time; this is
     * the same rule applied to the layer-list form, whose triggers are only known once the seed
     * fills. [anchor] is the seed fill (null for the seed layer itself).
     */
    private fun buildLayerOrder(
        layerId: String,
        parent: OrderRequest.Stack,
        layer: LayerSpec,
        qty: BigDecimal,
        triggerPrice: BigDecimal?,
        anchor: BigDecimal? = null,
    ): OrderRequest {
        val intent = layerEntryIntent(layerId, parent.symbol)
        val restsBehindAnchor =
            triggerPrice != null &&
                anchor != null &&
                (
                    (parent.side == Side.BUY && triggerPrice < anchor) ||
                        (parent.side == Side.SELL && triggerPrice > anchor)
                )
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
            layer.orderType is com.qkt.dsl.ast.Limit || restsBehindAnchor ->
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
        bracketEntryEstimateOrNull(req)
            ?: error("Cannot estimate entry price for bracket ${req.id}: no last price for ${req.symbol}")

    private fun bracketEntryEstimateOrNull(req: OrderRequest.Bracket): BigDecimal? =
        when (val entry = req.entry) {
            is OrderRequest.Stop -> entry.stopPrice
            is OrderRequest.Limit -> entry.limitPrice
            is OrderRequest.IfTouched -> entry.triggerPrice
            is OrderRequest.StopLimit -> entry.stopPrice
            else -> lastObservedPrice[req.symbol] ?: priceProvider.lastPrice(req.symbol)
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
        brackets.preFill[req.entry.id] = req
        if (req.takeProfitAst != null || req.stopLossAst != null) {
            brackets.fillAnchoredFallback[req.entry.id] = req
        }
        book.evict(req.id)
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
     * when the entry fills (via [children]) and, once armed, fires a close-by-ticket at the
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
        brackets.preFill[attached.id] = req
        if (req.takeProfitAst != null || req.stopLossAst != null) {
            brackets.fillAnchoredAttached[attached.id] = req
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
            children.hold(attached.id, listOf(managedStop))
        }
        exposure.register(attached)
        val ack = submitToBroker(attached)
        return SubmitAck(req.id, req.id, accepted = ack.accepted, rejectReason = ack.rejectReason)
    }

    private fun buildAttachedManagedStop(
        req: OrderRequest.Bracket,
        now: Long,
        entryPrice: BigDecimal? = null,
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
                    entryPrice = entryPrice ?: bracketEntryEstimate(req),
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
                    entryPrice = entryPrice ?: bracketEntryEstimate(req),
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
                    entryPrice = entryPrice ?: bracketEntryEstimate(req),
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
        if (!ack.accepted && book[request.id]?.state?.isTerminal != true) {
            update(request.id) { it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now()) }
            exposure.remove(request.id)
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
        val existingGroup = if (entry.id == request.id) null else exposure.remove(entry.id)
        exposure.register(request, existingGroup)
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
        children.hold(req.parent.id, req.children, req)
        exposure.register(exposureEntryRequest(req.parent))
        dispatch(req.parent)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    private fun holdPending(request: OrderRequest): SubmitAck {
        update(request.id) { it.copy(state = OrderState.PENDING, lastUpdatedAt = clock.now()) }
        val trailingSeed =
            if (request is OrderRequest.TrailingStop || request is OrderRequest.TrailingStopLimit) {
                lastObservedPrice[request.symbol] ?: priceProvider.lastPrice(request.symbol)
            } else {
                null
            }
        stops.startTracking(request, trailingSeed)
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
     * True while some active structure still points at [id], so reclaiming it would break a
     * later lookup: a pending timed-exit whose target is this order, or an active stack that
     * owns it as the parent, layer-one, or a pending/filled/closed layer. Per-order satellite
     * Most per-order satellite data is not a reference and is evicted on reclaim. A filled
     * client-emulated OCO leg is the exception: keep it until its sibling resolves so a late
     * second fill can still be identified and compensated after intervening ticks.
     */
    private fun isReferenced(id: String): Boolean {
        if (ocoGuard.isHoldingForSibling(id)) return true
        if (timeExits.values.any { it.target.id == id }) return true
        for (s in stacks.all()) {
            if (id == s.id || id == s.layerOneOrderId) return true
            if (id in s.pendingLayerIds || id in s.filledLayerIds || id in s.closedLayerIds) return true
        }
        return false
    }

    /** Drop a dead, unreferenced order and all its order-keyed satellite state. */
    private fun reclaim(id: String) {
        book.evict(id)
        stops.forget(id)
        siblings.remove(id)
        scaleOuts.forget(id)
        siblingCancels.forget(id)
        ocoGuard.forget(id)
        children.take(id)
        engineHeldCloseTickets.remove(id)
        exposure.remove(id)
    }

    private fun runGc() = book.drainGc()

    private fun track(managed: ManagedOrder) {
        book.put(managed)
        managed.request.strategyId
            .takeIf { it.isNotBlank() }
            ?.let(snapshots::remember)
        persistAll()
    }

    private fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ): Boolean {
        val current = book[id]
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
        book.put(updated)
        if (updated.state.isTerminal && !current.state.isTerminal) book.enqueueGc(id)
        persistAll()
        return true
    }

    private fun persistAll() = snapshots.persistAll()

    private fun persistSubmissionIntent(strategyId: String) = snapshots.persistSubmissionIntent(strategyId)

    /** Flushes HWM-only trailing-stop changes at the live heartbeat cadence. */
    fun persistTrailingStateIfDirty() = snapshots.persistTrailingStateIfDirty()

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
        val ticket = e.brokerOrderId
        if (ticket != null && e.clientOrderId in brackets.restoredAttachedEntries) {
            markRestoredAttachedEntryFilled(e.clientOrderId, ticket)
        }
        ocoSequencer.onAccepted(e.clientOrderId)
    }

    private fun onRejected(e: BrokerEvent.OrderRejected) {
        haltCancels.forget(e.clientOrderId)
        brackets.forgetEntry(e.clientOrderId)
        val unarmedChildren = children.take(e.clientOrderId)
        scaleOutTracker.discardBasis(e.clientOrderId)
        val applied =
            update(e.clientOrderId) {
                it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now())
            }
        if (!applied) return
        siblingCancels.forget(e.clientOrderId)
        ocoGuard.onRejected(e.clientOrderId, e.reason)
        exposure.remove(e.clientOrderId)
        scaleOutExits.completeExit(e.clientOrderId, OrderState.REJECTED)
        risk.forgetRejected(e.clientOrderId)
        unarmedChildren.orEmpty().forEach { cancel(it.id) }
        ocoSequencer.onRejected(e.clientOrderId)
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
        scaleOutTracker.onBasisPartiallyFilled(e)
        exposure.recordFill(e.clientOrderId, e.cumulativeFilled)
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
            siblingCancels.onExecution(e.clientOrderId)
        }
    }

    private fun onFilled(e: BrokerEvent.OrderFilled) {
        haltCancels.forget(e.clientOrderId)
        if (!e.updatesOrderExecution) {
            log.info(
                "position close observed order_id={} broker_order_id={} — terminal order record unchanged",
                e.clientOrderId,
                e.brokerOrderId,
            )
            completeAttachedBracketOnVenueClose(e)
            return
        }
        brackets.preFill.remove(e.clientOrderId)
        val existing = book[e.clientOrderId]
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
        ocoGuard.onFilled(e.clientOrderId)
        exposure.remove(e.clientOrderId)
        scaleOutExits.completeExit(e.clientOrderId, OrderState.FILLED)
        log.info(
            "order filled order_id={} strategy_id={} symbol={} side={} qty={} price={}",
            e.clientOrderId,
            e.strategyId,
            e.symbol,
            e.side,
            e.quantity,
            e.price,
        )
        val filledSibling = ocoGuard.filledSibling(e.clientOrderId)
        if (filledSibling != null) {
            discardChildrenForCompensatedOcoLeg(e.clientOrderId)
            ocoGuard.compensateDoubleFill(e, filledSibling)
            ocoSequencer.clearFor(e.clientOrderId)
            return
        }
        val pending = children.take(e.clientOrderId)
        val fallbackBracket = brackets.fillAnchoredFallback.remove(e.clientOrderId)
        val attachedBracket = brackets.fillAnchoredAttached.remove(e.clientOrderId)
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
                        venueProtection.attachBracket(
                            operationId,
                            ticket,
                            resolved.strategyId,
                            fallbackStop,
                            sl,
                            resolved.takeProfit,
                        )
                    }
                // A bracket restored before its symbol was quoted had no price to build its
                // engine-managed stop on; build it now from the fill it anchors to.
                val heldStop =
                    if (resolved.stopLoss !is StopLossSpec.Fixed &&
                        pending.orEmpty().none { it.id == "${resolved.id}-sl" } &&
                        book["${resolved.id}-sl"]?.state?.isTerminal != false
                    ) {
                        buildAttachedManagedStop(resolved, clock.now(), entryPrice = e.price)?.also { stop ->
                            track(
                                ManagedOrder(
                                    id = stop.id,
                                    request = stop,
                                    state = OrderState.CREATED,
                                    parentClientOrderId = resolved.id,
                                    createdAt = clock.now(),
                                    lastUpdatedAt = clock.now(),
                                ),
                            )
                        }
                    } else {
                        null
                    }
                (pending.orEmpty() + listOfNotNull(heldStop)).forEach { child ->
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
        scaleOutTracker.onBasisFilled(e)
        siblingCancels.onExecution(e.clientOrderId)
        siblingCancels.forget(e.clientOrderId)
        completeAttachedBracketOnEngineExit(e)
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
            book.orders.entries.filter { (id, managed) ->
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
        val entry = book[e.clientOrderId] ?: return
        if (entry.request !is OrderRequest.Bracket || entry.state != OrderState.FILLED) return
        val filled = entry.cumulativeFilledQuantity.takeIf { it.signum() > 0 } ?: entry.request.quantity
        completeAttachedBracketOnExit(
            entryId = entry.id,
            wrapperId = entry.parentClientOrderId,
            filledQuantity = filled,
            closedQuantity = e.quantity,
            closeTicket = e.brokerOrderId ?: entry.brokerOrderId,
        )
    }

    /**
     * An engine-held exit child (`-sl` / `-tp`) of a venue-attached bracket filled: the
     * position it protected is reduced or gone, exactly as after a venue-side close. Account
     * the closed quantity against the bracket's entry so the wrapper completes and releases
     * its exposure instead of staying pending until the next restart retires it as a phantom.
     * The entry's own record may already be reclaimed by then, so the filled quantity falls
     * back to the bracket's requested size.
     */
    private fun completeAttachedBracketOnEngineExit(e: BrokerEvent.OrderFilled) {
        if (!e.clientOrderId.endsWith("-sl") && !e.clientOrderId.endsWith("-tp")) return
        val wrapperId = book[e.clientOrderId]?.parentClientOrderId
        val wrapper = wrapperId?.let { book[it] }
        if (wrapper == null) {
            // Restored after a restart: the wrapper record is not persisted, but the attached
            // entry carries the position ticket this close-by-ticket just consumed.
            val ticket = e.brokerOrderId?.takeIf { it.isNotBlank() } ?: return
            val entry =
                book.orders.values.firstOrNull {
                    it.brokerOrderId == ticket &&
                        it.request is OrderRequest.Bracket &&
                        it.id == (it.request as OrderRequest.Bracket).entry.id
                } ?: return
            completeAttachedBracketOnExit(
                entryId = entry.id,
                wrapperId = null,
                filledQuantity = entry.cumulativeFilledQuantity.takeIf { it.signum() > 0 } ?: entry.request.quantity,
                closedQuantity = e.quantity,
                closeTicket = ticket,
            )
            return
        }
        val request = wrapper.request as? OrderRequest.Bracket ?: return
        if (wrapper.state.isTerminal) return
        val entryId = request.entry.id
        val entry = book[entryId]
        if (entry != null && entry.request !is OrderRequest.Bracket) return
        val filled = entry?.cumulativeFilledQuantity?.takeIf { it.signum() > 0 } ?: request.quantity
        completeAttachedBracketOnExit(
            entryId = entryId,
            wrapperId = wrapperId,
            filledQuantity = filled,
            closedQuantity = e.quantity,
            closeTicket = e.brokerOrderId ?: entry?.brokerOrderId,
        )
    }

    private fun completeAttachedBracketOnExit(
        entryId: String,
        wrapperId: String?,
        filledQuantity: BigDecimal,
        closedQuantity: BigDecimal,
        closeTicket: String?,
    ) {
        val closed = (brackets.venueClosedQuantityByEntry[entryId] ?: BigDecimal.ZERO) + closedQuantity
        if (closed < filledQuantity) {
            brackets.venueClosedQuantityByEntry[entryId] = closed
            return
        }
        brackets.venueClosedQuantityByEntry.remove(entryId)
        if (closeTicket != null) {
            val held = engineHeldCloseTickets.filterValues { it == closeTicket }.keys
            for (id in held) {
                val managed = book[id] ?: continue
                if (managed.state == OrderState.PENDING || managed.state == OrderState.CREATED) cancel(id)
            }
        }
        if (wrapperId == null) return
        val wrapper = book[wrapperId] ?: return
        if (wrapper.state.isTerminal) return
        for (childId in wrapper.childClientOrderIds) {
            val child = book[childId] ?: continue
            if (child.state == OrderState.PENDING || child.state == OrderState.CREATED) cancel(childId)
        }
        val liveChild = wrapper.childClientOrderIds.any { book[it]?.state?.isTerminal == false }
        if (liveChild) return
        update(wrapperId) { it.copy(state = OrderState.FILLED, lastUpdatedAt = clock.now()) }
        exposure.remove(wrapperId)
    }

    private fun discardChildrenForCompensatedOcoLeg(clientOrderId: String) {
        children.take(clientOrderId)
        brackets.fillAnchoredFallback.remove(clientOrderId)
        brackets.fillAnchoredAttached.remove(clientOrderId)
        scaleOutTracker.discardPendingBasis(clientOrderId)
    }

    private fun onCancelled(e: BrokerEvent.OrderCancelled) {
        haltCancels.forget(e.clientOrderId)
        brackets.forgetEntry(e.clientOrderId)
        val applied =
            update(e.clientOrderId) {
                it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now())
            }
        if (!applied) return
        siblingCancels.forget(e.clientOrderId)
        exposure.remove(e.clientOrderId)
        scaleOutExits.completeExit(e.clientOrderId, OrderState.CANCELLED)
        val unarmedChildren = children.take(e.clientOrderId)
        val pendingScaleOut = scaleOuts.pendingByBasis.remove(e.clientOrderId)
        val partialPositionTicket = scaleOuts.partialPositionTickets.remove(e.clientOrderId)
        unarmedChildren?.forEach { child -> cancel(child.id) }
        scaleOutTracker.onBasisCancelled(e.clientOrderId, pendingScaleOut, partialPositionTicket)
        log.info(
            "order cancelled order_id={} strategy_id={} reason={}",
            e.clientOrderId,
            e.strategyId,
            e.reason,
        )
        ocoSequencer.clearFor(e.clientOrderId)
    }

    private fun evaluateTriggers(tick: Tick) {
        lastObservedPrice[tick.symbol] = tick.price
        // Only this symbol's live orders drive trailing + trigger evaluation — O(this symbol),
        // not O(all live). An id in the index with no entry in [orders] is an invariant violation,
        // not an expected absence, so surface it.
        symbolLiveScratch.clear()
        book.liveIdsFor(tick.symbol)?.let { ids ->
            for (id in ids) {
                symbolLiveScratch.add(book[id] ?: error("live order index desync: $id"))
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
            stopTicker.onTick(managed, tick.price)
        }

        // Phase 38: sweep pending GTD orders past their deadline when the broker doesn't
        // self-cancel. Only runs when the venue can't self-expire — MT5 returns
        // supportsNativeGtd=true and skips it; PaperBroker, Bybit, and LogBroker fall through here.
        // Walks [gtdLive] (deadline-bearing orders only) and compares longs; the live order is
        // resolved only for the few that actually expired, in the same order a full scan would cancel.
        // One timestamp per pass: GTD, time-exit, and stack deadlines all compare against the same
        // tick instant. The empty guards keep the pass iterator-free when nothing has a deadline.
        val now = clock.now()
        if (!broker.supportsNativeGtd && book.gtdDeadlines.isNotEmpty()) {
            gtdExpiredScratch.clear()
            for ((id, deadline) in book.gtdDeadlines) {
                if (now >= deadline) gtdExpiredScratch.add(id)
            }
            for (i in gtdExpiredScratch.indices) {
                val managed = book[gtdExpiredScratch[i]] ?: continue
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
            if (managed.state == OrderState.PENDING && isTriggered(managed, tick, stops)) {
                triggeredScratch.add(managed)
            }
        }
        for (i in triggeredScratch.indices) {
            fireFallbackTrigger(triggeredScratch[i], tick.price)
        }

        runGc()
    }

    private fun handleTimeExitExpiry(te: OrderRequest.TimeExit) {
        val target = book[te.target.id] ?: return
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

    private fun fireFallbackTrigger(
        managed: ManagedOrder,
        tickPrice: BigDecimal,
    ) {
        // [triggeredScratch] is a snapshot. An earlier synchronous fill can cancel this order
        // before its turn in the loop; terminal-state protection rejects the state transition,
        // but without this guard the stale snapshot would still be submitted to the broker.
        if (book[managed.id]?.state != OrderState.PENDING) return
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
                    val level = stops.trailLevel(managed) ?: error("TrailingStopLimit level missing for ${managed.id}")
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
        exposure.remove(request.id)
        log.warn("engine-held order {} blocked before broker submission: {}", request.id, reason)
        bus.publish(com.qkt.events.RiskRejectedEvent(request, reason, timestamp = clock.now()))
    }

    /**
     * An exit carrying a [LegIntent.Close] closes exactly its own leg, so the net-based stale
     * sweep and reduce-only tripwire must not judge it: under a hedging book a short leg's BUY
     * stop while net-long is a legitimate exit (#1071).
     */
    private fun isLegLinked(clientOrderId: String): Boolean = book[clientOrderId]?.request?.legIntent is LegIntent.Close

    private fun managedStopCloseTicket(request: OrderRequest): String? =
        closeTicketFor?.invoke(request.strategyId, request.id)
            ?: closePrimaryTicketFor?.invoke(request.strategyId, request.symbol)

    private fun isEngineHeldOnRestore(request: OrderRequest): Boolean =
        when (request) {
            is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit -> true
            is OrderRequest.StopLimit ->
                OrderTypeCapability.STOP_LIMIT !in broker.capabilitiesFor(request.symbol)
            else -> isPersistentManagedStop(request)
        }

    fun pendingStackLayerInfos(): List<PendingStackLayerInfo> =
        stacks.all().flatMap { state ->
            state.pendingLayerIds.mapNotNull { layerId ->
                val managed = book[layerId] ?: return@mapNotNull null
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
