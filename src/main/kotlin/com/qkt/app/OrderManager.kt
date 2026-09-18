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
import com.qkt.app.order.FillHandler
import com.qkt.app.order.HaltCancellations
import com.qkt.app.order.ManagedStopBook
import com.qkt.app.order.ManagedStopTicker
import com.qkt.app.order.ObservedPrices
import com.qkt.app.order.OcoExecutionGuard
import com.qkt.app.order.OcoSequencer
import com.qkt.app.order.OrderBook
import com.qkt.app.order.OrderCancellation
import com.qkt.app.order.OrderEventHandlers
import com.qkt.app.order.OrderOps
import com.qkt.app.order.OrderRestorer
import com.qkt.app.order.OrderRouter
import com.qkt.app.order.OrderStateSnapshots
import com.qkt.app.order.PendingChildBook
import com.qkt.app.order.PendingExposureBook
import com.qkt.app.order.ProtectionLevels
import com.qkt.app.order.ProtectiveExitGuard
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
            log = log,
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
    private val venueRecovery: VenueRecovery =
        VenueRecovery(
            book,
            brackets,
            exposure,
            broker,
            bookedVenueTickets,
            clock,
            ops,
            log,
        ) { event -> eventHandlers.onCancelled(event) }
    private val restorer: OrderRestorer =
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
            log = log,
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
            log,
        )
    private val timeExits: TimeExits = TimeExits(book, exposure, clock, ops)
    private val cancellation: OrderCancellation =
        OrderCancellation(
            book,
            exposure,
            stacks,
            scaleOuts,
            scaleOutExits,
            haltCancels,
            closeTickets,
            broker,
            clock,
            ops,
            isRiskReducingForHalt,
        )
    private val tickEvaluation: TickEvaluation =
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
                    log = log,
                ),
            broker = broker,
            clock = clock,
            ops = ops,
            requireArmedTrailTicket = requireArmedTrailTicket,
            closeTicket = { request -> managedStopCloseTicket(request) },
            log = log,
        )
    private val venue: VenueSubmission = VenueSubmission(book, exposure, broker, bus, priceProvider, clock, ops, log)
    private val router: OrderRouter =
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
    private val exitGuard: ProtectiveExitGuard = ProtectiveExitGuard(book, ops, strategyNetQty, log)
    private val eventHandlers: OrderEventHandlers =
        OrderEventHandlers(
            book = book,
            exposure = exposure,
            children = children,
            brackets = brackets,
            haltCancels = haltCancels,
            risk = risk,
            ocoGuard = ocoGuard,
            ocoSequencer = ocoSequencer,
            siblingCancels = siblingCancels,
            scaleOuts = scaleOuts,
            scaleOutTracker = scaleOutTracker,
            scaleOutExits = scaleOutExits,
            venueRecovery = venueRecovery,
            clock = clock,
            ops = ops,
            log = log,
        )
    private val fillHandler: FillHandler =
        FillHandler(
            book = book,
            exposure = exposure,
            children = children,
            brackets = brackets,
            haltCancels = haltCancels,
            ocoGuard = ocoGuard,
            ocoSequencer = ocoSequencer,
            siblingCancels = siblingCancels,
            bracketFills = bracketFills,
            attachedCompletion = attachedCompletion,
            scaleOutTracker = scaleOutTracker,
            scaleOutExits = scaleOutExits,
            exitGuard = exitGuard,
            clock = clock,
            ops = ops,
            log = log,
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
     * generation must not depend on the fill handler having re-anchored relative children first.
     */
    fun entryRiskForFill(
        clientOrderId: String,
        quantity: BigDecimal,
        fillPrice: BigDecimal,
        symbol: String,
    ): EntryRiskReport? = risk.entryRiskForFill(clientOrderId, quantity, fillPrice, symbol)

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> eventHandlers.onAccepted(e) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> eventHandlers.onRejected(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fillHandler.onFilled(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> stackExecution.onLayerFilled(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> stackExecution.onExitFilled(e) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> eventHandlers.onPartiallyFilled(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> eventHandlers.onCancelled(e) }
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

    fun cancel(clientOrderId: String) = cancellation.cancel(clientOrderId)

    fun cancelPendingForSymbol(symbol: String) = cancellation.cancelPendingForSymbol(symbol)

    /**
     * Cancel active entry intent after a risk halt, optionally limited to [strategyId].
     *
     * Protective monitors, risk-reducing exits, and composite containers whose entry has filled
     * remain active. Their risk-increasing pending children are still cancelled individually.
     */
    fun cancelEntriesForHalt(strategyId: String? = null) = cancellation.cancelEntriesForHalt(strategyId)

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
    ): Int = cancellation.activeEntryOrderCount(strategyId, symbol)

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

    private fun rejectEngineHeld(
        request: OrderRequest,
        reason: String,
    ) = venue.rejectEngineHeld(request, reason)

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
