package com.qkt.app.order

import com.qkt.app.StackTracker
import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.At
import com.qkt.execution.Immediate
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Pyramiding stacks from submit to flat. Layer 1 (the seed) goes out first; its fill anchors
 * every later layer's trigger, which then rests engine-side or at the venue. Each filled layer
 * gets its own exits via [StackLayerExits]. When every filled layer has closed, the unfilled
 * layers are cancelled and the stack terminates.
 */
internal class StackExecution(
    private val stacks: StackTracker,
    private val layers: StackLayerOrders,
    private val exits: StackLayerExits,
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val siblings: SiblingLinks,
    private val broker: Broker,
    private val clock: Clock,
    private val ops: OrderOps,
    private val engineHeldSubmissionBlockReason: (OrderRequest) -> String?,
) {
    private val log = LoggerFactory.getLogger(StackExecution::class.java)

    fun submit(req: OrderRequest.Stack): SubmitAck {
        val firstLayer =
            req.plan.layers.firstOrNull()
                ?: error("StackPlan must have at least one layer")
        // Layer 1 may be Immediate (market) or At (pending limit/stop). Both are supported.
        stacks.register(req.id, req.plan, req.plan.outerBracket)
        val now = clock.now()
        val firstOrderId = "${req.id}-l1"
        stacks.setLayerOneOrderId(req.id, firstOrderId)
        val firstQty = layers.resolveLayerQuantity(firstLayer)
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
        val firstReq = layers.buildLayerOrder(firstOrderId, req, firstLayer, firstQty, triggerPrice = firstTriggerPrice)
        ops.track(
            ManagedOrder(
                id = firstOrderId,
                request = firstReq,
                state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        ops.update(req.id) {
            it.copy(
                state = OrderState.WORKING,
                childClientOrderIds = listOf(firstOrderId),
                lastUpdatedAt = now,
            )
        }
        exposure.register(firstReq)
        ops.dispatch(firstReq)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    fun onLayerFilled(e: BrokerEvent.OrderFilled) {
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
            exits.attachToVenue(
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
        val slDistance = exits.attachStopLoss(stackId = owner, layerOrderId = e.clientOrderId, fillPrice = e.price)
        val hadTp =
            exits.attachTakeProfit(
                stackId = owner,
                layerOrderId = e.clientOrderId,
                fillPrice = e.price,
                slDistance = slDistance,
            )
        if (slDistance != null && hadTp) {
            siblings.pair(slId, tpId)
        }
    }

    fun onExitFilled(e: BrokerEvent.OrderFilled) {
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
            cancelPending(stackId)
            stacks.terminate(stackId)
        }
    }

    fun cancelPending(stackId: String) {
        val state = stacks.get(stackId) ?: return
        for (pid in state.pendingLayerIds.toList()) ops.cancel(pid)
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
            val triggerPrice = layers.resolveTriggerPrice(layer.trigger, anchor)
            val layerOrderId = "$stackId-l${layer.index}"
            val qty = layers.resolveLayerQuantity(layer)
            val pending = layers.buildLayerOrder(layerOrderId, parent, layer, qty, triggerPrice, anchor)
            val now = clock.now()
            ops.track(
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
                ops.dispatch(pending)
            } else {
                ops.rejectEngineHeld(pending, blockReason)
            }
        }
    }
}
