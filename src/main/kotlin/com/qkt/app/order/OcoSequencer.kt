package com.qkt.app.order

import com.qkt.broker.SubmitAck
import com.qkt.common.Clock
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.isTerminal

/**
 * Places a [OrderRequest.StandaloneOCO] the venue cannot hold natively, one acceptance at a time:
 * leg2 is dispatched only after the venue accepts leg1, so a leg1 rejection can never leave a
 * one-legged (directional) OCO. Sequences are indexed under the id each leg's broker events
 * arrive under — the entry id for a bracket leg, the leg's own id otherwise (see [ocoFillId]).
 */
internal class OcoSequencer(
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val siblings: SiblingLinks,
    private val guard: OcoExecutionGuard,
    private val clock: Clock,
    private val ops: OrderOps,
) {
    private class Sequence(
        val ocoId: String,
        val leg1: OrderRequest,
        val leg1AckId: String,
        val leg2: OrderRequest,
        val leg2AckId: String,
    ) {
        var leg2Placed: Boolean = false
        var leg2Confirmed: Boolean = false

        /**
         * Set when leg1 fills before leg2's acceptance arrives: leg2's venue ticket isn't known
         * yet, so the sibling-cancel is deferred until leg2's acceptance and cancelled then.
         */
        var leg2PendingCancel: Boolean = false
    }

    /** Sequences awaiting leg1's acceptance, keyed by [Sequence.leg1AckId]. */
    private val byLeg1: MutableMap<String, Sequence> = mutableMapOf()

    /** Active/in-flight sequences keyed by [Sequence.leg2AckId], until the OCO resolves. */
    private val byLeg2: MutableMap<String, Sequence> = mutableMapOf()

    /** Tracks both legs of [req], links them, and places leg1. */
    fun submit(req: OrderRequest.StandaloneOCO): SubmitAck {
        val now = clock.now()
        ops.update(req.id) {
            it.copy(
                state = OrderState.WORKING,
                groupId = req.id,
                childClientOrderIds = listOf(req.leg1.id, req.leg2.id),
                lastUpdatedAt = now,
            )
        }
        for (leg in listOf(req.leg1, req.leg2)) {
            ops.track(
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
        // Sibling link keyed by the id each leg's fill arrives under, not the leg's own id.
        // A Bracket leg is placed as an OTO whose parent is the inner entry, so the broker
        // fills `Bracket.entry` (a distinct id) — keying by the bracket id would leave the
        // link unreachable and the sibling would never cancel on fill. Leaf legs (Stop/Limit)
        // fill under their own id, so this is a no-op for them. Acceptances/rejections arrive
        // under the same id, so the sequence below is keyed by it too.
        val leg1AckId = ocoFillId(req.leg1)
        val leg2AckId = ocoFillId(req.leg2)
        exposure.register(exposureEntryRequest(req.leg1), req.id)
        exposure.register(exposureEntryRequest(req.leg2), req.id)
        siblings.pair(leg1AckId, leg2AckId)
        guard.markEmulated(leg1AckId, req.id)
        guard.markEmulated(leg2AckId, req.id)

        // Event-driven sequencing: place leg1 now; leg2 only once the venue accepts leg1 (in
        // [onAccepted]). A leg1 rejection abandons the OCO with leg2 never sent — there is no
        // one-legged window. With a synchronous broker the acceptance fires inline during
        // dispatch, so the whole OCO resolves here re-entrantly; with an async broker the result
        // follows later on the bus. Either way the OCO's tracked state is the truth.
        val seq = Sequence(req.id, req.leg1, leg1AckId, req.leg2, leg2AckId)
        byLeg1[leg1AckId] = seq
        byLeg2[leg2AckId] = seq

        val ack1 = ops.dispatch(req.leg1)
        if (book[req.id]?.state == OrderState.REJECTED) {
            return SubmitAck(req.id, req.id, accepted = false, rejectReason = "leg ${req.leg1.id} rejected")
        }
        if (!ack1.accepted) {
            // Local rejection that carried no event (e.g. a capability reject) — abandon the
            // OCO; leg2 was never dispatched.
            exposure.remove(leg2AckId)
            clear(seq)
            return reject(req.id, "leg ${req.leg1.id} rejected: ${ack1.rejectReason ?: "unknown"}")
        }
        return SubmitAck(req.id, req.id, accepted = true)
    }

    /**
     * Advance any OCO whose leg the venue just accepted. Accepting leg1 releases leg2 (held back
     * so a leg1 rejection can't leave a one-legged OCO); accepting leg2 confirms its venue
     * ticket and fires a cancel that was deferred because leg1 filled while leg2 was still
     * unacknowledged.
     */
    fun onAccepted(ackId: String) {
        byLeg1[ackId]?.let { seq ->
            if (!seq.leg2Placed && book[seq.ocoId]?.state?.isTerminal != true) {
                seq.leg2Placed = true
                ops.dispatch(seq.leg2)
            }
        }
        byLeg2[ackId]?.let { seq ->
            seq.leg2Confirmed = true
            if (seq.leg2PendingCancel) {
                seq.leg2PendingCancel = false
                ops.cancel(seq.leg2.id)
                clear(seq)
            }
        }
    }

    /**
     * Abandon an OCO whose leg the venue rejected. A leg1 rejection means leg2 was never sent —
     * nothing to unwind. A leg2 rejection cancels the still-live leg1.
     */
    fun onRejected(ackId: String) {
        byLeg1[ackId]?.let { seq ->
            if (!seq.leg2Placed) {
                exposure.remove(seq.leg2AckId)
                clear(seq)
                reject(seq.ocoId, "leg ${seq.leg1.id} rejected")
                return
            }
        }
        byLeg2[ackId]?.let { seq ->
            clear(seq)
            ops.cancel(seq.leg1.id)
            reject(seq.ocoId, "leg ${seq.leg2.id} rejected")
        }
    }

    /**
     * True when [legId] is a placed leg2 the venue has not acknowledged yet: its ticket is
     * unknown, so a cancel now would no-op at the venue. The cancel is recorded and sent once
     * the acceptance arrives.
     */
    fun deferCancelUntilAccepted(legId: String): Boolean {
        val pending = byLeg2[legId] ?: return false
        if (!pending.leg2Placed || pending.leg2Confirmed) return false
        pending.leg2PendingCancel = true
        return true
    }

    /** Drops the sequence [ackId] belongs to, if any; its OCO has resolved. */
    fun clearFor(ackId: String) {
        (byLeg1[ackId] ?: byLeg2[ackId])?.let { clear(it) }
    }

    private fun clear(seq: Sequence) {
        byLeg1.remove(seq.leg1AckId)
        byLeg2.remove(seq.leg2AckId)
    }

    private fun reject(
        ocoId: String,
        reason: String,
    ): SubmitAck {
        ops.update(ocoId) { it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now()) }
        return SubmitAck(ocoId, ocoId, accepted = false, rejectReason = reason)
    }
}

/**
 * The clientOrderId under which [leg]'s fill is reported. A Bracket leg is placed as an OTO whose
 * parent is `Bracket.entry`, so the broker fills the inner entry — its id, not the bracket
 * wrapper's. Leaf legs (Stop/Limit) fill under their own id. Mirrors the compiler's
 * [com.qkt.dsl.compile.ActionCompiler.parentClientOrderIdFor].
 */
internal fun ocoFillId(leg: OrderRequest): String = (leg as? OrderRequest.Bracket)?.entry?.id ?: leg.id
