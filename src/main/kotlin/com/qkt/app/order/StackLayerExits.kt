package com.qkt.app.order

import com.qkt.app.StackTracker
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.exitLegIntent
import java.math.BigDecimal

/**
 * The protective exits of a filled stack layer, computed off the layer's actual fill. On a venue
 * that holds position SL/TP they are attached to the layer's position ticket; otherwise they
 * rest as a separate stop and limit, linked to cancel each other.
 */
internal class StackLayerExits(
    private val stacks: StackTracker,
    private val book: OrderBook,
    private val closeTickets: EngineHeldCloseTickets,
    private val venueProtection: VenuePositionProtection,
    private val clock: Clock,
    private val ops: OrderOps,
    private val closeTicketFor: ((String, String) -> String?)?,
) {
    /**
     * Attach a filled stack layer's fixed SL/TP to its venue position, so the broker closes that
     * exact ticket when a level is hit. The levels are computed off the actual fill (a stack fires
     * at market, so they aren't known until fill) — hence a position modify rather than the entry
     * wire. Used when the broker supports [OrderTypeCapability.POSITION_MODIFY]; without it the
     * layer's exits rest as separate orders (see [attachStopLoss] / [attachTakeProfit]).
     */
    fun attachToVenue(
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
            ops.reportProtectionFailure(
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

    fun attachStopLoss(
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
        ops.track(
            ManagedOrder(
                id = slId,
                request = slReq,
                state = OrderState.CREATED,
                parentClientOrderId = layerOrderId,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        ops.update(layerOrderId) {
            it.copy(childClientOrderIds = it.childClientOrderIds + slId, lastUpdatedAt = now)
        }
        if (engineHeldCloseTicket != null) {
            closeTickets[slId] = engineHeldCloseTicket
            ops.update(slId) { it.copy(state = OrderState.PENDING, lastUpdatedAt = clock.now()) }
        } else {
            ops.dispatch(slReq)
        }
        return (fillPrice - slPrice).abs()
    }

    fun attachTakeProfit(
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
        ops.track(
            ManagedOrder(
                id = tpId,
                request = tpReq,
                state = OrderState.CREATED,
                parentClientOrderId = layerOrderId,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        ops.update(layerOrderId) {
            it.copy(childClientOrderIds = it.childClientOrderIds + tpId, lastUpdatedAt = now)
        }
        ops.dispatch(tpReq)
        return true
    }
}
