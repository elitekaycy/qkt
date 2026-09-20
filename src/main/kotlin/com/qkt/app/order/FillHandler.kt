package com.qkt.app.order

import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderState
import com.qkt.execution.isTerminal
import org.slf4j.Logger

/**
 * A full fill, in order: book it (a fill for an already-terminal order is refused — cumulative
 * execution is immutable), release its exposure, then either compensate an emulated OCO that
 * filled twice or release what waited on the fill (bracket exits, held children, scale-out
 * exits, sibling cancels), and finally check the protective-exit invariants. A fill that only
 * reports a venue position close leaves the order record untouched.
 */
internal class FillHandler(
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val children: PendingChildBook,
    private val brackets: BracketBook,
    private val haltCancels: HaltCancellations,
    private val ocoGuard: OcoExecutionGuard,
    private val ocoSequencer: OcoSequencer,
    private val siblingCancels: SiblingCancellation,
    private val bracketFills: BracketFills,
    private val attachedCompletion: AttachedBracketCompletion,
    private val scaleOutTracker: ScaleOutTracker,
    private val scaleOutExits: ScaleOutExits,
    private val exitGuard: ProtectiveExitGuard,
    private val clock: Clock,
    private val ops: OrderOps,
    private val log: Logger,
) {
    /** An order filled completely: book it, then release what waited on it. */
    fun onFilled(e: BrokerEvent.OrderFilled) {
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
            ops.update(e.clientOrderId) {
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
        exitGuard.onExitFilled(e)
        exitGuard.retireStale(e.strategyId, e.symbol)
    }

    private fun discardChildrenForCompensatedOcoLeg(clientOrderId: String) {
        children.take(clientOrderId)
        brackets.fillAnchoredFallback.remove(clientOrderId)
        brackets.fillAnchoredAttached.remove(clientOrderId)
        scaleOutTracker.discardPendingBasis(clientOrderId)
    }
}
