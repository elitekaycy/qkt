package com.qkt.app.order

import com.qkt.common.Clock
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.isTerminal

/**
 * What happens when a venue that holds a bracket's exits as separate orders (every venue but an
 * attached MT5 bracket) refuses one leg of the exit OCO after the entry filled. Abandoning the OCO,
 * as a standalone one is, would leave the position with no stop and no target: the refused stop is
 * held engine-side instead, paired with the target that still rests, and a refused target still lets
 * the stop go out. Either way the operator is alerted, as when the venue refuses an attached bracket.
 */
internal class BracketExitRefusal(
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val siblings: SiblingLinks,
    private val guard: OcoExecutionGuard,
    private val clock: Clock,
    private val ops: OrderOps,
) {
    /**
     * Whether [ocoId] is a bracket's exit OCO, whose legs protect the bracket's filled entry: built at the
     * fill under the bracket, or released by the fill as the child of the OTO that decomposes the bracket.
     */
    fun protectsPosition(ocoId: String): Boolean =
        ocoId.endsWith(EXIT_OCO) &&
            book[ocoId.removeSuffix(EXIT_OCO)]?.request.let { it is OrderRequest.Bracket || it is OrderRequest.OTO }

    /** The venue refused [target]; the stop is still sent, and the operator told the target is missing. */
    fun targetRefused(target: OrderRequest) =
        ops.reportProtectionFailure(
            target.strategyId,
            "the venue refused take-profit ${target.id}; the stop still protects",
        )

    /**
     * The venue refused [stop] while the exit OCO [ocoId]'s target [targetAckId] rests: the stop is held
     * engine-side, firing a market close at its level, and cancels or is cancelled by the target.
     */
    fun holdRefusedStop(
        ocoId: String,
        stop: OrderRequest,
        target: OrderRequest,
        targetAckId: String,
    ) {
        val held = (stop as? OrderRequest.Stop)?.copy(id = "${stop.id}-held", timestamp = clock.now())
        if (held == null || book[target.id]?.state?.isTerminal == true) {
            ops.reportProtectionFailure(
                stop.strategyId,
                "the venue refused stop ${stop.id}; the position may be unprotected",
            )
            return
        }
        val now = clock.now()
        ops.track(
            ManagedOrder(
                id = held.id,
                request = held,
                state = OrderState.PENDING,
                parentClientOrderId = ocoId,
                groupId = ocoId,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        exposure.register(held, ocoId)
        siblings.pair(targetAckId, held.id)
        guard.markEmulated(held.id, ocoId)
        ops.persistAll()
        ops.reportProtectionFailure(
            stop.strategyId,
            "the venue refused stop ${stop.id}; holding it engine-side as ${held.id} at ${held.stopPrice}",
        )
    }

    private companion object {
        const val EXIT_OCO = "-oco"
    }
}
