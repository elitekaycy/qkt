package com.qkt.app

import com.qkt.app.order.EntryRiskReport
import com.qkt.app.order.OrderReactions
import com.qkt.app.order.OrderSettings
import com.qkt.app.order.OrderWorkflows
import com.qkt.app.order.ProtectionLevels
import com.qkt.app.order.intrabarFillFor
import com.qkt.app.order.isPersistentManagedStop
import com.qkt.app.order.pendingStackLayerInfos
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
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.StatePersistor
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
 * This class is the public surface; the work is done by focused collaborators in
 * [com.qkt.app.order] (routing, brackets, stacks, OCO, fills, tick triggers, restore), all of
 * which log under this class's logger so operator log routing is unchanged.
 *
 * One per [LiveSession] / `Backtest` run; not thread-safe.
 */
class OrderManager(
    broker: Broker,
    bus: EventBus,
    priceProvider: MarketPriceProvider,
    clock: Clock,
    persistor: StatePersistor = NoopStatePersistor(),
    /**
     * The venue ticket an engine-managed exit (strategyId, clientOrderId) closes, so a fired stop
     * closes its own hedging position instead of opening a counter; null means a netting close.
     */
    closeTicketFor: ((String, String) -> String?)? = null,
    /** Fallback resolver for a plain bracket whose armed trail closes the PRIMARY position. */
    closePrimaryTicketFor: ((String, String) -> String?)? = null,
    /** Live hedging sessions must never turn a missing close ticket into an opposite market order. */
    requireArmedTrailTicket: Boolean = false,
    /** Venue metadata used to report bracket risk in account units (`price distance x qty x contractSize`). */
    instruments: InstrumentRegistry = NoopInstrumentRegistry,
    /** Record per-bracket risk for the backtest report ([riskUsdFor]); live leaves it off so nothing accrues. */
    trackRisk: Boolean = false,
    /** Raises an operator alert when a filled position cannot retain venue-side protection. */
    onProtectionFailure: (strategyId: String, message: String) -> Unit = { _, _ -> },
    /** A rejection reason when an engine-held request may no longer reach the broker (a halt re-check). */
    engineHeldSubmissionBlockReason: (OrderRequest) -> String? = { null },
    /** Identifies requests that reduce current exposure and must survive a halt cancel sweep. */
    isRiskReducingForHalt: (OrderRequest) -> Boolean = { false },
    /** Net strategy position for (strategyId, symbol), used to keep protective exits reduce-only (#1069). */
    strategyNetQty: ((strategyId: String, symbol: String) -> BigDecimal)? = null,
    /** How the venue accounts positions on a symbol; read per submitted request, never per tick or fill. */
    positionMode: (symbol: String) -> PositionAccountingMode = { PositionAccountingMode.UNKNOWN },
    /** Venue tickets the position ledger already holds for a strategy, so restore does not republish them (#1096). */
    bookedVenueTickets: (strategyId: String) -> Set<String> = { emptySet() },
) : PendingOrderExposureProvider {
    private val settings =
        OrderSettings(
            broker,
            bus,
            priceProvider,
            clock,
            persistor,
            closeTicketFor,
            closePrimaryTicketFor,
            requireArmedTrailTicket,
            instruments,
            trackRisk,
            onProtectionFailure,
            engineHeldSubmissionBlockReason,
            isRiskReducingForHalt,
            strategyNetQty,
            positionMode,
            bookedVenueTickets,
        )
    private val log = LoggerFactory.getLogger(OrderManager::class.java)
    private val workflows = OrderWorkflows(settings, log)
    private val reactions = OrderReactions(workflows, settings, log)
    private val store = workflows.store

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> reactions.eventHandlers.onAccepted(e) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> reactions.eventHandlers.onRejected(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> reactions.fillHandler.onFilled(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> workflows.stackExecution.onLayerFilled(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> workflows.stackExecution.onExitFilled(e) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> reactions.eventHandlers.onPartiallyFilled(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> reactions.eventHandlers.onCancelled(e) }
        bus.subscribe<BrokerEvent.OrderCancelFailed> { e -> workflows.haltCancels.onCancelFailed(e.clientOrderId) }
        bus.subscribe<BrokerEvent.PositionModificationCompleted> { e -> workflows.venueProtection.onCompleted(e) }
        bus.subscribe<TickEvent> { e -> reactions.tickEvaluation.onTick(e.tick) }
    }

    /** Plans, validates, tracks and routes [request]; re-submitting a live order id is a no-op ack. */
    fun submit(request: OrderRequest): SubmitAck = workflows.router.submit(request)

    /** Cancels [clientOrderId]; a composite cascades to its children. */
    fun cancel(clientOrderId: String) = workflows.cancellation.cancel(clientOrderId)

    /** Cancels every pending stack and resting or engine-held order on [symbol]. */
    fun cancelPendingForSymbol(symbol: String) = workflows.cancellation.cancelPendingForSymbol(symbol)

    /**
     * Cancel active entry intent after a risk halt, optionally limited to [strategyId]. Protective
     * monitors, risk-reducing exits, and composite containers whose entry has filled remain active.
     */
    fun cancelEntriesForHalt(strategyId: String? = null) = workflows.cancellation.cancelEntriesForHalt(strategyId)

    /** Retry halt-owned cancellations that have not produced a terminal broker event. */
    fun retryHaltCancellations(nowMs: Long) = workflows.haltCancels.retry(nowMs)

    /**
     * Rebuild pending order tracking and sibling linkage from the persistor for [strategyIds].
     * Venue-held orders are handed to the broker for reconciliation; engine-held orders resume as
     * [OrderState.PENDING] monitors. Called once at session startup; a persistence read failure
     * aborts startup rather than silently discarding live order state.
     */
    fun restore(strategyIds: List<String>) = reactions.restorer.restore(strategyIds)

    /** Flushes HWM-only trailing-stop changes at the live heartbeat cadence. */
    fun persistTrailingStateIfDirty() = store.snapshots.persistTrailingStateIfDirty()

    /** Entries this strategy has in flight on [side], one per OCO group plus each ungrouped order. */
    override fun orderCountFor(
        side: Side,
        strategyId: String?,
    ): Int = store.exposure.orderCountFor(side, strategyId)

    override fun symbolsFor(strategyId: String?): Set<String> = store.exposure.symbolsFor(strategyId)

    override fun quantityFor(
        symbol: String,
        side: Side,
        strategyId: String?,
    ): BigDecimal = store.exposure.quantityFor(symbol, side, strategyId)

    /** Count active, risk-increasing entry orders for [strategyId] on [symbol]; O(this symbol's live orders). */
    fun activeEntryOrderCount(
        strategyId: String,
        symbol: String,
    ): Int = workflows.cancellation.activeEntryOrderCount(strategyId, symbol)

    fun getOrder(clientOrderId: String): ManagedOrder? = store.book[clientOrderId]

    fun activeOrders(): List<ManagedOrder> =
        store.book.orders.values
            .filter { !it.state.isTerminal }

    fun pendingOrders(): List<ManagedOrder> =
        store.book.orders.values
            .filter { it.state == OrderState.PENDING }

    /** Sibling order ids linked to [clientOrderId] — exposed for restart-recovery tests. */
    fun siblingsOf(clientOrderId: String): List<String> = store.siblings[clientOrderId]

    /** Symbol, side, and quantity submitted under [clientOrderId]. */
    data class OrderDetails(
        val symbol: String,
        val side: Side,
        val quantity: BigDecimal,
    )

    /**
     * Recover the originating symbol/side/quantity for [clientOrderId] — the fields a
     * [BrokerEvent.OrderRejected] event omits. A rejected order is retained only until the next GC
     * drain (a tick), so read this synchronously within the rejection handler.
     */
    fun orderDetailsFor(clientOrderId: String): OrderDetails? =
        store.book[clientOrderId]?.request?.let { OrderDetails(it.symbol, it.side, it.quantity) }

    /** Active protective orders that require ticks on the engine thread to trigger. */
    fun engineHeldProtectiveStopCount(): Int =
        store.book.orders.values.count { managed ->
            !managed.state.isTerminal && (managed.id in store.closeTickets || isPersistentManagedStop(managed.request))
        }

    /**
     * Read-only: whether a live order on [symbol] could fill within the bar range `[low, high]`;
     * backs the tick-resolved fill replay's decision to decode a bar's ticks. See [intrabarFillFor].
     */
    fun intrabarFill(
        symbol: String,
        low: BigDecimal,
        high: BigDecimal,
        maxHalfSpread: BigDecimal = BigDecimal.ZERO,
    ): IntrabarFill = intrabarFillFor(store.book, workflows.timeExits, store.stacks, symbol, low, high, maxHalfSpread)

    /** Returns and removes the recorded risk for [clientOrderId]; consumed once per fill. */
    fun riskUsdFor(clientOrderId: String): BigDecimal? = store.risk.riskUsdFor(clientOrderId)

    /** Resolved venue prices attached to an entry fill; consumed once by the backtest report. */
    fun protectionFor(clientOrderId: String): ProtectionLevels? = store.risk.protectionFor(clientOrderId)

    /** Resolve and consume an entry bracket against the broker's actual [fillPrice] and [quantity]. */
    fun entryRiskForFill(
        clientOrderId: String,
        quantity: BigDecimal,
        fillPrice: BigDecimal,
        symbol: String,
    ): EntryRiskReport? = store.risk.entryRiskForFill(clientOrderId, quantity, fillPrice, symbol)

    /** Engine-held stack layers still waiting for their trigger, for status reporting. */
    fun pendingStackLayerInfos(): List<PendingStackLayerInfo> = pendingStackLayerInfos(store.book, store.stacks)

    /** One engine-held stack layer awaiting its trigger. */
    data class PendingStackLayerInfo(
        val stackId: String,
        val layer: Int,
        val triggerPrice: BigDecimal,
        val side: String,
        val quantity: BigDecimal,
    )
}
