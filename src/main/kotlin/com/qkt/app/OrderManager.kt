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
import com.qkt.execution.TrailMode
import com.qkt.execution.TriggerType
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
) {
    private val log = LoggerFactory.getLogger(OrderManager::class.java)

    private val orders: MutableMap<String, ManagedOrder> = mutableMapOf()

    private val trailingHwm: MutableMap<String, BigDecimal> = mutableMapOf()

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

            is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit -> holdPending(request)

            is OrderRequest.StandaloneOCO -> submitOco(request)

            is OrderRequest.OTO -> submitOto(request)

            is OrderRequest.Bracket -> {
                val entryEstimate = priceProvider.lastPrice(request.symbol) ?: BigDecimal.ZERO
                if (entryEstimate.signum() != 0) {
                    recordRisk(
                        clientOrderIds = listOf(request.id, request.entry.id),
                        quantity = request.quantity,
                        entry = entryEstimate,
                        stop = request.stopLoss,
                    )
                }
                if (OrderTypeCapability.BRACKET in broker.capabilitiesFor(request.symbol)) {
                    submitToBroker(request)
                } else {
                    submitBracketFallback(request)
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
        val sl =
            OrderRequest.Stop(
                id = "${req.id}-sl",
                symbol = req.symbol,
                side = exitSide,
                quantity = req.quantity,
                stopPrice = req.stopLoss,
                timeInForce = req.timeInForce,
                timestamp = clock.now(),
                strategyId = req.strategyId,
            )
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
        return submit(oto)
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
        siblings[req.leg1.id] = listOf(req.leg2.id)
        siblings[req.leg2.id] = listOf(req.leg1.id)

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

    private fun track(managed: ManagedOrder) {
        orders[managed.id] = managed
        persistAll()
    }

    private fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ) {
        orders[id]?.let { orders[id] = change(it) }
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
            val strategies = (pendingByStrategy.keys + pairsByStrategy.keys).toSet()
            for (sid in strategies) {
                persistor.savePendingOrders(sid, pendingByStrategy[sid] ?: emptyMap())
                persistor.saveBracketPairs(sid, pairsByStrategy[sid] ?: emptyList())
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
        for (managed in orders.values.toList()) {
            if (managed.state != OrderState.PENDING) continue
            if (managed.request.symbol != tick.symbol) continue
            updateTrailingHwm(managed, tick.price)
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
            orders.values
                .filter { it.state == OrderState.PENDING }
                .filter { it.request.symbol == tick.symbol }
                .filter { triggerHit(it, tick.price) }
                .toList()
        for (managed in triggered) {
            fireFallbackTrigger(managed, tick.price)
        }
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
        val params = trailParams(managed.request) ?: return
        val current = trailingHwm[managed.id]
        when (params.side) {
            Side.SELL -> if (current == null || tickPrice > current) trailingHwm[managed.id] = tickPrice
            Side.BUY -> if (current == null || tickPrice < current) trailingHwm[managed.id] = tickPrice
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
        val params = trailParams(managed.request) ?: return null
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
