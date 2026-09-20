package com.qkt.app.order

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TriggerType
import java.math.BigDecimal

/**
 * The exits of a filled [OrderRequest.ScaleOut]: one engine-held take-profit per leg, each
 * closing its fraction of the position by ticket. The wrapper completes when its last exit
 * resolves. On a venue that holds several positions per symbol an exit without an owned ticket
 * could close the wrong position, so none is armed and an operator alert is raised instead.
 */
internal class ScaleOutExits(
    private val scaleOuts: ScaleOutBook,
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val broker: Broker,
    private val bus: EventBus,
    private val clock: Clock,
    private val ops: OrderOps,
    private val requireArmedTrailTicket: Boolean,
) {
    /** An exit reached [terminalState]; the wrapper completes with its last exit. */
    fun completeExit(
        exitId: String,
        terminalState: OrderState,
    ) {
        val scaleOutId = scaleOuts.wrapperByExitId.remove(exitId) ?: return
        val remaining = scaleOuts.remainingExitIds[scaleOutId] ?: return
        remaining.remove(exitId)
        if (remaining.isNotEmpty()) {
            ops.persistAll()
            return
        }
        scaleOuts.remainingExitIds.remove(scaleOutId)
        scaleOuts.activeById.remove(scaleOutId)
        ops.update(scaleOutId) { it.copy(state = terminalState, lastUpdatedAt = clock.now()) }
    }

    /** Arms [scaleOut]'s exits against a basis that filled [basisQuantity]. */
    fun activate(
        scaleOut: OrderRequest.ScaleOut,
        basisQuantity: BigDecimal,
        positionTicket: String?,
    ) {
        if (requireArmedTrailTicket &&
            OrderTypeCapability.MULTI_POSITION_PER_SYMBOL in broker.capabilitiesFor(scaleOut.symbol) &&
            positionTicket == null
        ) {
            ops.reportProtectionFailure(
                scaleOut.strategyId,
                "ScaleOut ${scaleOut.id} basis ${scaleOut.basis.id} completed without an owned position ticket; " +
                    "no opposite exit orders were armed",
            )
            ops.update(scaleOut.id) { it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now()) }
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
        armExits(scaleOut, exitRequests)
    }

    private fun armExits(
        scaleOut: OrderRequest.ScaleOut,
        exits: List<OrderRequest.IfTouched>,
    ) {
        val now = clock.now()
        val exitIds = exits.mapTo(linkedSetOf()) { it.id }
        scaleOuts.activeById[scaleOut.id] = scaleOut
        scaleOuts.remainingExitIds[scaleOut.id] = exitIds
        for (exit in exits) {
            scaleOuts.wrapperByExitId[exit.id] = scaleOut.id
            book.put(
                ManagedOrder(
                    id = exit.id,
                    request = exit,
                    state = OrderState.PENDING,
                    parentClientOrderId = scaleOut.id,
                    createdAt = now,
                    lastUpdatedAt = now,
                ),
            )
            exposure.register(exit)
        }
        book[scaleOut.id]?.let { wrapper ->
            book.put(
                wrapper.copy(
                    childClientOrderIds = listOf(scaleOut.basis.id) + exitIds,
                    lastUpdatedAt = now,
                ),
            )
        }
        ops.persistSubmissionIntent(scaleOut.strategyId)
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
}
