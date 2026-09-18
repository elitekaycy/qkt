package com.qkt.app

import com.qkt.app.order.AttachedBracketCompletion
import com.qkt.app.order.BracketBook
import com.qkt.app.order.BracketExits
import com.qkt.app.order.BracketFills
import com.qkt.app.order.BracketRiskRecorder
import com.qkt.app.order.BracketSubmission
import com.qkt.app.order.CompositeRestore
import com.qkt.app.order.EngineHeldCloseTickets
import com.qkt.app.order.EngineHeldRestore
import com.qkt.app.order.EntryRiskReport
import com.qkt.app.order.HaltCancellations
import com.qkt.app.order.ManagedStopBook
import com.qkt.app.order.ManagedStopTicker
import com.qkt.app.order.ObservedPrices
import com.qkt.app.order.OcoExecutionGuard
import com.qkt.app.order.OcoSequencer
import com.qkt.app.order.OrderBook
import com.qkt.app.order.OrderOps
import com.qkt.app.order.OrderRestorer
import com.qkt.app.order.OrderRouter
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
import com.qkt.app.order.StackExecution
import com.qkt.app.order.StackLayerExits
import com.qkt.app.order.StackLayerOrders
import com.qkt.app.order.TickEvaluation
import com.qkt.app.order.TimeExits
import com.qkt.app.order.TriggerFiring
import com.qkt.app.order.VenuePositionProtection
import com.qkt.app.order.VenueRecovery
import com.qkt.app.order.VenueSubmission
import com.qkt.app.order.blendAvg
import com.qkt.app.order.intrabarFillFor
import com.qkt.app.order.isPersistentManagedStop
import com.qkt.broker.Broker
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.isTerminal
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
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

    private val book: OrderBook =
        OrderBook(
            isReferenced = { id -> isReferenced(id) },
            reclaim = { id -> reclaim(id) },
        )
    private val exposure = PendingExposureBook(book)
    private val risk = BracketRiskRecorder(trackRisk, instruments)
    private val haltCancels: HaltCancellations =
        HaltCancellations(book, broker, clock) { strategyId, message ->
            reportProtectionFailure(strategyId, message)
        }

    private val stops = ManagedStopBook()
    private val stopTicker: ManagedStopTicker =
        ManagedStopTicker(
            stops = stops,
            clock = clock,
            persist = { persistAll() },
            tightenAtVenue = { managed, level, transition -> venueProtection.ratchet(managed, level, transition) },
        )

    private val prices = ObservedPrices(priceProvider)
    private val closeTickets = EngineHeldCloseTickets()

    private val siblings = SiblingLinks()
    private val ops: OrderOps =
        object : OrderOps {
            override fun submit(request: OrderRequest): SubmitAck = this@OrderManager.submit(request)

            override fun dispatch(request: OrderRequest): SubmitAck = this@OrderManager.dispatch(request)

            override fun cancel(clientOrderId: String) = this@OrderManager.cancel(clientOrderId)

            override fun track(managed: ManagedOrder) = this@OrderManager.track(managed)

            override fun update(
                id: String,
                change: (ManagedOrder) -> ManagedOrder,
            ): Boolean = this@OrderManager.update(id, change)

            override fun submitToBroker(request: OrderRequest): SubmitAck = this@OrderManager.submitToBroker(request)

            override fun submitRegisteredToBroker(request: OrderRequest): SubmitAck =
                this@OrderManager.submitRegisteredToBroker(request)

            override fun rejectEngineHeld(
                request: OrderRequest,
                reason: String,
            ) = this@OrderManager.rejectEngineHeld(request, reason)

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
    private val venueProtection: VenuePositionProtection =
        VenuePositionProtection(
            broker = broker,
            bus = bus,
            ops = ops,
            closeTicket = { request -> managedStopCloseTicket(request) },
            armStackFallbackStop = { stackId, layerOrderId, fillPrice, ticket ->
                stackExits.attachStopLoss(stackId, layerOrderId, fillPrice, engineHeldCloseTicket = ticket)
            },
            armBracketFallbackStop = { stop, ticket -> armFillAnchoredFallbackStop(stop, ticket) },
        )
    private val scaleOuts = ScaleOutBook()
    private val scaleOutExits =
        ScaleOutExits(scaleOuts, book, exposure, broker, bus, clock, ops, requireArmedTrailTicket)
    private val scaleOutTracker = ScaleOutTracker(scaleOuts, scaleOutExits, book, exposure, clock, ops)
    private val scaleOutRecovery = ScaleOutRecovery(scaleOuts, book, exposure, clock)

    private val children = PendingChildBook()
    private val brackets = BracketBook()
    private val snapshots =
        OrderStateSnapshots(persistor, book, children, brackets, scaleOutRecovery, siblings, stops)
    private val bracketExits = BracketExits(prices, clock)
    private val bracketSubmission =
        BracketSubmission(broker, priceProvider, bracketExits, risk, brackets, children, exposure, book, clock, ops)
    private val bracketFills = BracketFills(book, brackets, bracketExits, venueProtection, clock, ops)
    private val attachedCompletion = AttachedBracketCompletion(book, brackets, closeTickets, exposure, clock, ops)
    private val venueRecovery =
        VenueRecovery(book, brackets, exposure, broker, bookedVenueTickets, clock, ops) { event -> onCancelled(event) }
    private val restorer =
        OrderRestorer(
            persistor = persistor,
            book = book,
            siblings = siblings,
            ocoGuard = ocoGuard,
            exposure = exposure,
            scaleOuts = scaleOuts,
            scaleOutRecovery = scaleOutRecovery,
            composites = CompositeRestore(book, children, brackets, bracketExits, exposure, broker, clock, ops),
            engineHeld = EngineHeldRestore(book, stops, exposure, broker, clock),
            venueRecovery = venueRecovery,
            snapshots = snapshots,
            broker = broker,
            clock = clock,
        )

    private val stacks: StackTracker = StackTracker()
    private val stackLayers: StackLayerOrders = StackLayerOrders(clock, positionMode)
    private val stackExits: StackLayerExits =
        StackLayerExits(stacks, book, closeTickets, venueProtection, clock, ops, closeTicketFor)
    private val stackExecution: StackExecution =
        StackExecution(
            stacks,
            stackLayers,
            stackExits,
            book,
            exposure,
            siblings,
            broker,
            clock,
            ops,
            engineHeldSubmissionBlockReason,
        )
    private val timeExits = TimeExits(book, exposure, clock, ops)
    private val tickEvaluation =
        TickEvaluation(
            book = book,
            prices = prices,
            stops = stops,
            stopTicker = stopTicker,
            timeExits = timeExits,
            stacks = stacks,
            stackExecution = stackExecution,
            firing =
                TriggerFiring(
                    book = book,
                    stacks = stacks,
                    stops = stops,
                    closeTickets = closeTickets,
                    broker = broker,
                    clock = clock,
                    ops = ops,
                    closeTicket = { request -> managedStopCloseTicket(request) },
                    engineHeldSubmissionBlockReason = engineHeldSubmissionBlockReason,
                ),
            broker = broker,
            clock = clock,
            ops = ops,
            requireArmedTrailTicket = requireArmedTrailTicket,
            closeTicket = { request -> managedStopCloseTicket(request) },
        )
    private val venue = VenueSubmission(book, exposure, broker, bus, priceProvider, clock, ops)
    private val router =
        OrderRouter(
            book = book,
            exposure = exposure,
            stops = stops,
            prices = prices,
            children = children,
            venue = venue,
            ocoSequencer = ocoSequencer,
            bracketSubmission = bracketSubmission,
            scaleOutTracker = scaleOutTracker,
            timeExits = timeExits,
            stackExecution = stackExecution,
            broker = broker,
            bus = bus,
            clock = clock,
            ops = ops,
            positionMode = positionMode,
        )

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
        bus.subscribe<BrokerEvent.OrderFilled> { e -> stackExecution.onLayerFilled(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> stackExecution.onExitFilled(e) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> onPartiallyFilled(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> onCancelled(e) }
        bus.subscribe<BrokerEvent.OrderCancelFailed> { e -> onCancelFailed(e) }
        bus.subscribe<BrokerEvent.PositionModificationCompleted> { e -> venueProtection.onCompleted(e) }
        bus.subscribe<TickEvent> { e -> tickEvaluation.onTick(e.tick) }
    }

    fun submit(request: OrderRequest): SubmitAck = router.submit(request)

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
        if (managed.id in closeTickets) return true
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
    fun restore(strategyIds: List<String>) = restorer.restore(strategyIds)

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
    ): IntrabarFill = intrabarFillFor(book, timeExits, stacks, symbol, low, high, maxHalfSpread)

    fun pendingOrders(): List<ManagedOrder> = book.orders.values.filter { it.state == OrderState.PENDING }

    /** Active protective orders that require ticks on the engine thread to trigger. */
    fun engineHeldProtectiveStopCount(): Int =
        book.orders.values.count { managed ->
            !managed.state.isTerminal &&
                (
                    managed.id in closeTickets ||
                        isPersistentManagedStop(managed.request)
                )
        }

    private fun dispatch(request: OrderRequest): SubmitAck = router.dispatch(request)

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
        closeTickets[stop.id] = ticket
        exposure.register(stop)
        persistAll()
    }

    private fun submitToBroker(request: OrderRequest): SubmitAck = venue.submitToBroker(request)

    private fun submitRegisteredToBroker(request: OrderRequest): SubmitAck = venue.submitRegisteredToBroker(request)

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
        if (timeExits.targets(id)) return true
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
        closeTickets.remove(id)
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
            venueRecovery.markAttachedEntryFilled(e.clientOrderId, ticket)
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
            attachedCompletion.onVenueClose(e)
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
        bracketFills.armExits(e, pending)
        scaleOutTracker.onBasisFilled(e)
        siblingCancels.onExecution(e.clientOrderId)
        siblingCancels.forget(e.clientOrderId)
        attachedCompletion.onEngineExit(e)
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

    private fun rejectEngineHeld(
        request: OrderRequest,
        reason: String,
    ) = venue.rejectEngineHeld(request, reason)

    /**
     * An exit carrying a [LegIntent.Close] closes exactly its own leg, so the net-based stale
     * sweep and reduce-only tripwire must not judge it: under a hedging book a short leg's BUY
     * stop while net-long is a legitimate exit (#1071).
     */
    private fun isLegLinked(clientOrderId: String): Boolean = book[clientOrderId]?.request?.legIntent is LegIntent.Close

    private fun managedStopCloseTicket(request: OrderRequest): String? =
        closeTicketFor?.invoke(request.strategyId, request.id)
            ?: closePrimaryTicketFor?.invoke(request.strategyId, request.symbol)

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
