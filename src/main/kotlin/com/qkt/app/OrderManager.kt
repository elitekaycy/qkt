package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
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
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.At
import com.qkt.execution.ExpiryAction
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TrailMode
import com.qkt.execution.TriggerType
import com.qkt.execution.isCompositeShape
import com.qkt.execution.isTerminal
import com.qkt.execution.withStrategyId
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.Tick
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
) {
    private val log = LoggerFactory.getLogger(OrderManager::class.java)

    private val orders: MutableMap<String, ManagedOrder> = mutableMapOf()

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
     * Ids awaiting reclamation. An order is enqueued when it goes terminal in [update]; [runGc]
     * drains it once per pass, reclaiming it if nothing references it and re-queuing it otherwise.
     */
    private val gcQueue: ArrayDeque<String> = ArrayDeque()

    private val trailingHwm: MutableMap<String, BigDecimal> = mutableMapOf()

    /**
     * One-way arming state for [OrderRequest.ArmedTrailingStop] orders. `false` while
     * the stop sits at `entry ± distance`; flips to `true` once MFE crosses the
     * threshold and the stop starts trailing [OrderRequest.ArmedTrailingStop.hwm].
     * Never reverts. See #48.
     */
    private val armedTrailArmed: MutableMap<String, Boolean> = mutableMapOf()

    private val lastObservedPrice: MutableMap<String, BigDecimal> = mutableMapOf()

    private val siblings: MutableMap<String, List<String>> = mutableMapOf()

    private val pendingChildren: MutableMap<String, List<OrderRequest>> = mutableMapOf()

    private val scaleOutLegs: MutableMap<String, Pair<OrderRequest.ScaleOut, BigDecimal>> = mutableMapOf()

    private val timeExits: MutableMap<String, OrderRequest.TimeExit> = mutableMapOf()

    private val stacks: StackTracker = StackTracker()

    private val riskByClientOrderId: MutableMap<String, BigDecimal> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Returns and removes the recorded risk for [clientOrderId]. Designed to be called once per
     * fill — the entry is consumed so the map doesn't grow unbounded over a long-running session.
     */
    fun riskUsdFor(clientOrderId: String): BigDecimal? = riskByClientOrderId.remove(clientOrderId)

    private fun recordRisk(
        clientOrderIds: List<String>,
        quantity: BigDecimal,
        entry: BigDecimal,
        stop: BigDecimal,
    ) {
        val risk = entry.subtract(stop).abs().multiply(quantity)
        for (id in clientOrderIds) riskByClientOrderId[id] = risk
    }

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> onAccepted(e) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> onRejected(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> onFilled(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> onStackLayerFilled(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> evaluateStackFlat(e) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> onPartiallyFilled(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> onCancelled(e) }
        bus.subscribe<TickEvent> { e -> evaluateTriggers(e.tick) }
    }

    fun submit(request: OrderRequest): SubmitAck {
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
        return dispatch(request)
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
            return
        }
        if (managed.childClientOrderIds.isNotEmpty()) {
            for (childId in managed.childClientOrderIds) cancel(childId)
            update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            return
        }
        when (managed.state) {
            OrderState.CREATED, OrderState.PENDING ->
                update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
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
        // Cancel any remaining (non-stack) pending orders for the symbol that aren't already
        // children of a stack we just cancelled.
        val pending =
            orders.values
                .filter { it.state == OrderState.PENDING && it.request.symbol == symbol }
                .map { it.id }
        for (id in pending) cancel(id)
    }

    fun getOrder(clientOrderId: String): ManagedOrder? = orders[clientOrderId]

    /** Sibling order ids linked to [clientOrderId] — exposed for restart-recovery tests. */
    fun siblingsOf(clientOrderId: String): List<String> = siblings[clientOrderId].orEmpty()

    /**
     * Rebuild OCO leg tracking and sibling linkage from the persistor for [strategyIds],
     * then hand the legs to the broker so it can reconcile them against venue truth —
     * re-seeding still-pending legs and republishing any fill missed during downtime.
     * Called once at session startup. Best-effort: a persistor failure leaves state empty.
     */
    fun restore(strategyIds: List<String>) {
        val recovered = mutableListOf<ManagedOrder>()
        for (sid in strategyIds) {
            runCatching {
                for (leg in persistor.loadOcoLegs(sid)) {
                    if (orders.containsKey(leg.clientOrderId)) continue
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
                    recovered += managed
                }
            }.onFailure { e -> log.warn("[restore] failed for {}: {}", sid, e.message) }
        }
        if (recovered.isNotEmpty()) {
            runCatching { broker.recoverPendingOrders(recovered) }
                .onFailure { e -> log.warn("[restore] broker recovery failed: {}", e.message) }
        }
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
                if (OrderTypeCapability.IF_TOUCHED in broker.capabilitiesFor(request.symbol)) {
                    submitToBroker(request)
                } else {
                    holdPending(request)
                }

            is OrderRequest.TrailingStop,
            is OrderRequest.TrailingStopLimit,
            is OrderRequest.ArmedTrailingStop,
            -> holdPending(request)

            is OrderRequest.StandaloneOCO -> submitOco(request)

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
                        }
                    recordRisk(
                        clientOrderIds = listOf(request.id, request.entry.id),
                        quantity = request.quantity,
                        entry = entryEstimate,
                        stop = riskStop,
                    )
                }
                val caps = broker.capabilitiesFor(request.symbol)
                val isArmedTrail = request.stopLoss is StopLossSpec.ArmedTrail
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
                    !isArmedTrail && OrderTypeCapability.BRACKET in caps -> submitToBroker(request)
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
        val now = clock.now()
        update(req.id) {
            it.copy(
                state = OrderState.WORKING,
                childClientOrderIds = listOf(req.basis.id),
                lastUpdatedAt = now,
            )
        }
        track(
            ManagedOrder(
                id = req.basis.id,
                request = req.basis,
                state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        scaleOutLegs[req.basis.id] = req to req.basis.quantity
        dispatch(req.basis)
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
        dispatch(firstReq)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    private fun onStackLayerFilled(e: BrokerEvent.OrderFilled) {
        val owner = stacks.markFilled(e.clientOrderId) ?: return
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
    ) {
        val resolvedTicket = ticket?.takeIf { it.isNotBlank() } ?: return
        val state = stacks.get(stackId) ?: return
        val parent = (orders[stackId]?.request as? OrderRequest.Stack) ?: return
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
        broker.modifyPosition(resolvedTicket, sl = slPrice, tp = tpPrice)
    }

    private fun attachLayerSl(
        stackId: String,
        layerOrderId: String,
        fillPrice: BigDecimal,
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
        dispatch(slReq)
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
        val tpReq =
            OrderRequest.Limit(
                id = tpId,
                symbol = parent.symbol,
                side = exitSide,
                quantity = (orders[layerOrderId]?.request?.quantity ?: return false),
                limitPrice = tpPrice,
                timeInForce = parent.timeInForce,
                timestamp = clock.now(),
                strategyId = parent.strategyId,
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
                val distance = fillPrice.multiply(evaluateAt(childPrice.frac, fillPrice), Money.CONTEXT)
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
        // The fill is on an SL/TP closing a layer's position. Walk up to the layer-entry.
        val parentId = managed.parentClientOrderId ?: return
        // If parentId is the Stack itself, this is a layer-entry fill — handled by onStackLayerFilled.
        val parent = orders[parentId] ?: return
        if (parent.request is OrderRequest.Stack) return
        // parentId is a layer-entry; this SL/TP fill closes its position. Record it.
        val stackId = stacks.markLayerClosed(parentId) ?: return
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
            log.info(
                "stack pending stack_id={} strategy_id={} layer={} qty={} trigger={} side={}",
                stackId,
                parent.strategyId,
                layer.index,
                qty,
                triggerPrice,
                parent.side,
            )
            dispatch(pending)
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
    ): OrderRequest =
        when {
            triggerPrice == null ->
                OrderRequest.Market(
                    id = layerId,
                    symbol = parent.symbol,
                    side = parent.side,
                    quantity = qty,
                    timeInForce = parent.timeInForce,
                    timestamp = clock.now(),
                    strategyId = parent.strategyId,
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
                )
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

    private fun submitBracketFallback(req: OrderRequest.Bracket): SubmitAck {
        val exitSide = if (req.side == Side.BUY) Side.SELL else Side.BUY
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
        orders.remove(req.id)
        liveOrderIds.remove(req.id)
        liveBySymbol[req.symbol]?.remove(req.id)
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
     * `registerIndependentOpen`, poller close attribution).
     *
     * The engine still runs the trail on top: the [OrderRequest.ArmedTrailingStop] is dispatched
     * when the entry fills (via [pendingChildren]) and, once armed, fires a close-by-ticket at the
     * tightened level — finer than the static venue stop, which remains the offline backstop.
     */
    private fun submitBracketAttached(req: OrderRequest.Bracket): SubmitAck {
        val exitSide = if (req.side == Side.BUY) Side.SELL else Side.BUY
        val now = clock.now()
        // Ship keyed under the ENTRY id so the venue attaches the SL/TP to the position AND the
        // fill — with its ticket — flows through the same entry.id paths the position tracking
        // uses (registerIndependentOpen / registerStackOpen, sibling-cancel, poller close
        // attribution). A native bracket keyed under its own id would fill under the bracket id
        // and silently miss those registrations.
        val attached = req.copy(id = req.entry.id)
        // An armed trail is engine-managed on top of the venue's static pre-arm stop: dispatched
        // on the entry fill, it fires close-by-ticket at the tightened level. A fixed bracket has
        // no engine exit — the venue's attached SL/TP closes it outright.
        val trail =
            (req.stopLoss as? StopLossSpec.ArmedTrail)?.let { spec ->
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
                )
            }
        update(req.id) {
            it.copy(
                state = OrderState.WORKING,
                childClientOrderIds = listOfNotNull(attached.id, trail?.id),
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
        if (trail != null) {
            track(
                ManagedOrder(
                    id = trail.id,
                    request = trail,
                    state = OrderState.CREATED,
                    parentClientOrderId = req.id,
                    createdAt = now,
                    lastUpdatedAt = now,
                ),
            )
            // Arm the trail only once the position exists — dispatched on the entry's fill.
            pendingChildren[attached.id] = listOf(trail)
        }
        val ack = submitToBroker(attached)
        return SubmitAck(req.id, req.id, accepted = ack.accepted, rejectReason = ack.rejectReason)
    }

    private fun submitToBroker(request: OrderRequest): SubmitAck {
        update(request.id) { it.copy(state = OrderState.SUBMITTED, lastUpdatedAt = clock.now()) }
        return broker.submit(request)
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
        // Key the sibling link by the id each leg's fill arrives under, not the leg's own
        // id. A Bracket leg is placed as an OTO whose parent is the inner entry, so the
        // broker fills `Bracket.entry` (a distinct id) — keying by the bracket id would
        // leave the link unreachable and the sibling would never cancel on fill. Leaf legs
        // (Stop/Limit) fill under their own id, so this is a no-op for them.
        val fill1 = ocoFillId(req.leg1)
        val fill2 = ocoFillId(req.leg2)
        siblings[fill1] = listOf(fill2)
        siblings[fill2] = listOf(fill1)

        // Place legs one at a time and unwind on rejection — a half-placed OCO would
        // run one-legged (a directional bet, not a hedge).
        val ack1 = dispatch(req.leg1)
        if (!ack1.accepted) {
            cancel(req.leg2.id)
            return rejectOco(req.id, "leg ${req.leg1.id} rejected: ${ack1.rejectReason ?: "unknown"}")
        }
        val ack2 = dispatch(req.leg2)
        if (!ack2.accepted) {
            cancel(req.leg1.id)
            return rejectOco(req.id, "leg ${req.leg2.id} rejected: ${ack2.rejectReason ?: "unknown"}")
        }
        return SubmitAck(req.id, req.id, accepted = true)
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
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = request.id,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = request.id,
            accepted = true,
        )
    }

    /** Keep [liveOrderIds] + [liveBySymbol] in sync: a non-terminal order is live, a terminal one is not. */
    private fun indexLive(managed: ManagedOrder) {
        val symbol = managed.request.symbol
        if (managed.state.isTerminal) {
            liveOrderIds.remove(managed.id)
            liveBySymbol[symbol]?.remove(managed.id)
        } else {
            liveOrderIds.add(managed.id)
            liveBySymbol.getOrPut(symbol) { LinkedHashSet() }.add(managed.id)
        }
    }

    /**
     * True while some active structure still points at [id], so reclaiming it would break a
     * later lookup: a pending timed-exit whose target is this order, or an active stack that
     * owns it as the parent, layer-one, or a pending/filled/closed layer. Per-order satellite
     * data (siblings, trailing state) is NOT a reference — it is read synchronously during the
     * order's own terminal transition and evicted on reclaim, never read afterwards.
     */
    private fun isReferenced(id: String): Boolean {
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
        trailingHwm.remove(id)
        armedTrailArmed.remove(id)
        siblings.remove(id)
        scaleOutLegs.remove(id)
        pendingChildren.remove(id)
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
        indexLive(managed)
        persistAll()
    }

    private fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ) {
        orders[id]?.let {
            val wasTerminal = it.state.isTerminal
            val updated = change(it)
            orders[id] = updated
            indexLive(updated)
            // Enqueue once, on the transition into terminal — a redundant terminal->terminal
            // update (e.g. a replayed fill) must not re-queue the id.
            if (updated.state.isTerminal && !wasTerminal) gcQueue.addLast(id)
        }
        persistAll()
    }

    /**
     * Snapshot pending orders + bracket pairs per strategy to the configured persistor.
     * Best-effort: failures inside the persistor are swallowed so the order pipeline keeps
     * running. Called after every state mutation by [track] / [update] / [recordSiblings].
     */
    private fun persistAll() {
        runCatching {
            val pendingByStrategy: MutableMap<String, MutableMap<String, com.qkt.execution.OrderRequest>> =
                mutableMapOf()
            val pairsByStrategy: MutableMap<String, MutableList<com.qkt.persistence.BracketPair>> = mutableMapOf()

            for ((id, managed) in orders) {
                if (managed.state == OrderState.PENDING || managed.state == OrderState.CREATED) {
                    val sid = managed.request.strategyId
                    if (sid.isBlank()) continue
                    // Composite shapes (OCO, OTO, Bracket, ScaleOut, TimeExit, Stack) are
                    // engine-internal containers — the broker only ever sees their decomposed
                    // leaf orders. Recovery flows through dedicated channels (oco-legs.json,
                    // bracket pairs, stack tier state), so the composite parent itself is
                    // never persisted via savePendingOrders.
                    if (managed.request.isCompositeShape()) continue
                    pendingByStrategy.getOrPut(sid) { mutableMapOf() }[id] = managed.request
                }
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
            val strategies = (pendingByStrategy.keys + pairsByStrategy.keys + ocoLegsByStrategy.keys).toSet()
            for (sid in strategies) {
                persistor.savePendingOrders(sid, pendingByStrategy[sid] ?: emptyMap())
                persistor.saveBracketPairs(sid, pairsByStrategy[sid] ?: emptyList())
                persistor.saveOcoLegs(sid, ocoLegsByStrategy[sid] ?: emptyList())
            }
        }
    }

    private fun onAccepted(e: BrokerEvent.OrderAccepted) {
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
        log.info(
            "order accepted order_id={} strategy_id={} broker_order_id={}",
            e.clientOrderId,
            e.strategyId,
            e.brokerOrderId,
        )
    }

    private fun onRejected(e: BrokerEvent.OrderRejected) {
        update(e.clientOrderId) {
            it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now())
        }
    }

    private fun onPartiallyFilled(e: BrokerEvent.OrderPartiallyFilled) {
        update(e.clientOrderId) {
            it.copy(
                state = OrderState.PARTIALLY_FILLED,
                cumulativeFilledQuantity = e.cumulativeFilled,
                avgFillPrice = blendAvg(it.avgFillPrice, it.cumulativeFilledQuantity, e.price, e.quantity),
                lastUpdatedAt = clock.now(),
            )
        }
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
    }

    private fun onFilled(e: BrokerEvent.OrderFilled) {
        update(e.clientOrderId) {
            val newCumulative = it.cumulativeFilledQuantity + e.quantity
            it.copy(
                state = OrderState.FILLED,
                cumulativeFilledQuantity = newCumulative,
                avgFillPrice = blendAvg(it.avgFillPrice, it.cumulativeFilledQuantity, e.price, e.quantity),
                lastUpdatedAt = clock.now(),
            )
        }
        log.info(
            "order filled order_id={} strategy_id={} symbol={} side={} qty={} price={}",
            e.clientOrderId,
            e.strategyId,
            e.symbol,
            e.side,
            e.quantity,
            e.price,
        )
        pendingChildren.remove(e.clientOrderId)?.forEach { dispatch(it) }
        scaleOutLegs.remove(e.clientOrderId)?.let { (scaleReq, basisQty) ->
            val exitSide = if (scaleReq.side == Side.BUY) Side.SELL else Side.BUY
            scaleReq.legs.forEachIndexed { idx, leg ->
                val legQty =
                    basisQty
                        .multiply(leg.fraction)
                        .setScale(Money.SCALE, Money.ROUNDING)
                val legReq =
                    OrderRequest.IfTouched(
                        id = "${scaleReq.id}-leg-$idx",
                        symbol = scaleReq.symbol,
                        side = exitSide,
                        quantity = legQty,
                        triggerPrice = leg.priceTarget,
                        onTrigger = TriggerType.MARKET,
                        timeInForce = scaleReq.timeInForce,
                        timestamp = clock.now(),
                    )
                submit(legReq)
            }
        }
        siblings[e.clientOrderId]?.forEach { sibId ->
            val sib = orders[sibId] ?: return@forEach
            if (!sib.state.isTerminal) cancel(sibId)
        }
    }

    private fun onCancelled(e: BrokerEvent.OrderCancelled) {
        update(e.clientOrderId) {
            it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now())
        }
        log.info(
            "order cancelled order_id={} strategy_id={} reason={}",
            e.clientOrderId,
            e.strategyId,
            e.reason,
        )
    }

    private fun evaluateTriggers(tick: Tick) {
        lastObservedPrice[tick.symbol] = tick.price
        // Only this symbol's live orders drive trailing + trigger evaluation — O(this symbol),
        // not O(all live). An id in the index with no entry in [orders] is an invariant violation,
        // not an expected absence, so surface it.
        val symbolLive =
            liveBySymbol[tick.symbol]?.map { orders[it] ?: error("live order index desync: $it") }
                ?: emptyList()
        for (managed in symbolLive) {
            if (managed.state != OrderState.PENDING) continue
            updateTrailingHwm(managed, tick.price)
        }

        // Phase 38: sweep pending GTD orders past their deadline when the broker doesn't
        // self-cancel. This is the one all-symbols pass, but it only runs when the venue can't
        // self-expire — MT5 returns supportsNativeGtd=true and skips it; PaperBroker, Bybit, and
        // LogBroker fall through here.
        if (!broker.supportsNativeGtd) {
            val nowMs = clock.now()
            val allLive = liveOrderIds.map { orders[it] ?: error("live order index desync: $it") }
            for (managed in allLive) {
                if (managed.state.isTerminal) continue
                if (managed.state != OrderState.PENDING && managed.state != OrderState.WORKING) continue
                val deadline = managed.request.expiresAt ?: continue
                if (nowMs > deadline) cancel(managed.id)
            }
        }

        val now = clock.now()
        val expired =
            timeExits.values
                .filter { now >= it.deadline.toEpochMilli() }
                .toList()
        for (te in expired) {
            timeExits.remove(te.id)
            handleTimeExitExpiry(te)
        }

        val nowEpoch = clock.now()
        for (state in stacks.all()) {
            val deadline = state.deadlineEpochMs ?: continue
            if (nowEpoch < deadline) continue
            cancelStackPending(state.id)
            stacks.terminate(state.id)
        }

        val triggered: List<ManagedOrder> =
            symbolLive
                .filter { it.state == OrderState.PENDING }
                .filter { triggerHit(it, tick.price) }
        for (managed in triggered) {
            fireFallbackTrigger(managed, tick.price)
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
                trailingHwm[managed.id] = newHwm

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
                    }
                }
            }
            else -> {
                val params = trailParams(request) ?: return
                val current = trailingHwm[managed.id]
                when (params.side) {
                    Side.SELL -> if (current == null || tickPrice > current) trailingHwm[managed.id] = tickPrice
                    Side.BUY -> if (current == null || tickPrice < current) trailingHwm[managed.id] = tickPrice
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

    private fun triggerHit(
        managed: ManagedOrder,
        tickPrice: BigDecimal,
    ): Boolean =
        when (val request = managed.request) {
            is OrderRequest.Stop ->
                if (request.side == Side.BUY) tickPrice >= request.stopPrice else tickPrice <= request.stopPrice
            is OrderRequest.StopLimit ->
                if (request.side == Side.BUY) tickPrice >= request.stopPrice else tickPrice <= request.stopPrice
            is OrderRequest.IfTouched ->
                if (request.side == Side.BUY) tickPrice <= request.triggerPrice else tickPrice >= request.triggerPrice
            is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit -> {
                val params = trailParams(request) ?: return false
                val level = trailLevel(managed) ?: return false
                if (params.side == Side.SELL) tickPrice <= level else tickPrice >= level
            }
            is OrderRequest.ArmedTrailingStop -> {
                val level = trailLevel(managed) ?: return false
                // Exit SELL fires when price falls to the stop. Exit BUY fires when
                // price rises to the stop. Matches [OrderRequest.TrailingStop] semantics.
                if (request.side == Side.SELL) tickPrice <= level else tickPrice >= level
            }
            else -> false
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
        update(managed.id) { it.copy(state = OrderState.SUBMITTED, lastUpdatedAt = clock.now()) }
        val internal: OrderRequest =
            when (val req = managed.request) {
                is OrderRequest.Stop ->
                    OrderRequest.Market(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                    )
                is OrderRequest.StopLimit ->
                    OrderRequest.Limit(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        limitPrice = req.limitPrice,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
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
                    )
                is OrderRequest.ArmedTrailingStop ->
                    OrderRequest.Market(
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side,
                        quantity = req.quantity,
                        timeInForce = req.timeInForce,
                        timestamp = clock.now(),
                        // Close the exact venue position by ticket when this exit belongs to an
                        // independent leg (hedging) — otherwise a plain market opens a counter.
                        closesTicket = closeTicketFor?.invoke(req.strategyId, req.id),
                    )
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
                    )
                }
                else -> error("Not a Tier 2 fallback type: ${req::class.simpleName}")
            }
        broker.submit(internal)
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
