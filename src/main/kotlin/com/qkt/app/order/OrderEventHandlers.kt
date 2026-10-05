package com.qkt.app.order

import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderState
import org.slf4j.Logger

/**
 * Applies the broker's order-lifecycle events other than a full fill (see [FillHandler]) to the
 * order book and to every workflow that depends on the order: OCO sequencing, sibling cancels,
 * held children, scale-outs, exposure, halt cancels and the backtest risk records.
 */
internal class OrderEventHandlers(
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val children: PendingChildBook,
    private val brackets: BracketBook,
    private val haltCancels: HaltCancellations,
    private val risk: BracketRiskRecorder,
    private val ocoGuard: OcoExecutionGuard,
    private val ocoSequencer: OcoSequencer,
    private val siblingCancels: SiblingCancellation,
    private val scaleOuts: ScaleOutBook,
    private val scaleOutTracker: ScaleOutTracker,
    private val scaleOutExits: ScaleOutExits,
    private val venueRecovery: VenueRecovery,
    private val attachedCompletion: AttachedBracketCompletion,
    private val bracketFills: BracketFills,
    private val clock: Clock,
    private val ops: OrderOps,
    private val log: Logger,
) {
    /** The venue (or the engine, for held orders) accepted an order. */
    fun onAccepted(e: BrokerEvent.OrderAccepted) {
        val applied =
            ops.update(e.clientOrderId) {
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

    /** The venue rejected an order: unwind its composite state and dependants. */
    fun onRejected(e: BrokerEvent.OrderRejected) {
        haltCancels.forget(e.clientOrderId)
        brackets.forgetEntry(e.clientOrderId)
        val unarmedChildren = children.take(e.clientOrderId)
        scaleOutTracker.discardBasis(e.clientOrderId)
        val applied =
            ops.update(e.clientOrderId) {
                it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now())
            }
        if (!applied) return
        siblingCancels.forget(e.clientOrderId)
        ocoGuard.onRejected(e.clientOrderId, e.reason)
        exposure.remove(e.clientOrderId)
        scaleOutExits.completeExit(e.clientOrderId, OrderState.REJECTED)
        risk.forgetRejected(e.clientOrderId)
        unarmedChildren.orEmpty().forEach { ops.cancel(it.id) }
        ocoSequencer.onRejected(e.clientOrderId)
        attachedCompletion.onEntryEnded(e.clientOrderId, OrderState.REJECTED)
    }

    /** An order partially filled; its first execution slice already cancels its siblings. */
    fun onPartiallyFilled(e: BrokerEvent.OrderPartiallyFilled) {
        // Before the update: its snapshot persists the fill progress with the ticket this execution reported.
        scaleOutTracker.onBasisPartiallyFilled(e)
        val applied =
            ops.update(e.clientOrderId) {
                it.copy(
                    state = OrderState.PARTIALLY_FILLED,
                    cumulativeFilledQuantity = e.cumulativeFilled,
                    avgFillPrice = blendAvg(it.avgFillPrice, it.cumulativeFilledQuantity, e.price, e.quantity),
                    lastUpdatedAt = clock.now(),
                )
            }
        if (!applied) return
        exposure.recordFill(e.clientOrderId, e.cumulativeFilled)
        ocoGuard.onPartialExecution(e.clientOrderId, e.brokerOrderId)
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

    /**
     * An order was cancelled: release its exposure, cancel held children, arm partial scale-outs and
     * the exits of a bracket entry the venue filled in part.
     */
    fun onCancelled(e: BrokerEvent.OrderCancelled) {
        haltCancels.forget(e.clientOrderId)
        val bracket = brackets.preFill[e.clientOrderId]
        val anchoredAtFill = e.clientOrderId in brackets.fillAnchoredFallback
        brackets.forgetEntry(e.clientOrderId)
        val applied =
            ops.update(e.clientOrderId) {
                it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now())
            }
        if (!applied) return
        siblingCancels.forget(e.clientOrderId)
        exposure.remove(e.clientOrderId)
        scaleOutExits.completeExit(e.clientOrderId, OrderState.CANCELLED)
        val unarmedChildren = children.take(e.clientOrderId)
        val pendingScaleOut = scaleOuts.pendingByBasis.remove(e.clientOrderId)
        val partialPositionTicket = scaleOuts.partialPositionTickets.remove(e.clientOrderId)
        // An OCO leg that filled in part after its sibling executed is closed, not protected.
        val compensated = ocoGuard.compensateCancelledExecution(e.clientOrderId)
        val held = !unarmedChildren.isNullOrEmpty() || anchoredAtFill
        if (compensated || !bracketFills.armPartFilled(e.clientOrderId, bracket, held)) {
            unarmedChildren?.forEach { ops.cancel(it.id) }
        }
        attachedCompletion.onEntryEnded(e.clientOrderId, OrderState.CANCELLED)
        scaleOutTracker.onBasisCancelled(
            e.clientOrderId,
            pendingScaleOut.takeUnless { compensated },
            partialPositionTicket,
        )
        log.info(
            "order cancelled order_id={} strategy_id={} reason={}",
            e.clientOrderId,
            e.strategyId,
            e.reason,
        )
        ocoSequencer.clearFor(e.clientOrderId)
    }
}
