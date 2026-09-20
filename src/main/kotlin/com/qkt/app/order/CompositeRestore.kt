package com.qkt.app.order

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.Clock
import com.qkt.dsl.ast.ChildAt
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.withStrategyId

/**
 * Rebuilds composite orders (OTO, bracket) from a restart snapshot. Each is re-created in the
 * shape its submit path gave it, with its not-yet-armed children held until the parent fills.
 * Orders the venue must confirm are added to `recovered` for venue reconciliation.
 */
internal class CompositeRestore(
    private val book: OrderBook,
    private val children: PendingChildBook,
    private val brackets: BracketBook,
    private val bracketExits: BracketExits,
    private val exposure: PendingExposureBook,
    private val broker: Broker,
    private val clock: Clock,
    private val ops: OrderOps,
) {
    /** Re-creates a pending OTO: its wrapper, live parent, and children held until the parent fills. */
    fun restoreOto(
        request: OrderRequest.OTO,
        recovered: MutableList<ManagedOrder>,
    ) {
        require(request.parent.id != request.id) { "OTO ${request.id} parent must have a distinct id" }
        require(request.children.none { it.id == request.id || it.id == request.parent.id }) {
            "OTO ${request.id} child ids must differ from the wrapper and parent ids"
        }
        require(
            request.children
                .map { it.id }
                .distinct()
                .size == request.children.size,
        ) {
            "OTO ${request.id} child ids must be unique"
        }
        require(book[request.id] == null && request.children.none { book[it.id] != null }) {
            "persisted OTO ${request.id} collides with already-restored order state"
        }
        val now = clock.now()
        val childIds = request.children.map { it.id }
        val wrapper =
            ManagedOrder(
                id = request.id,
                request = request,
                state = OrderState.WORKING,
                childClientOrderIds = listOf(request.parent.id) + childIds,
                createdAt = now,
                lastUpdatedAt = now,
            )
        book.put(wrapper)

        val parent =
            ManagedOrder(
                id = request.parent.id,
                request = request.parent,
                state = OrderState.WORKING,
                parentClientOrderId = request.id,
                createdAt = now,
                lastUpdatedAt = now,
            )
        book.put(parent)
        for (child in request.children) {
            val managed =
                ManagedOrder(
                    id = child.id,
                    request = child,
                    state = OrderState.CREATED,
                    parentClientOrderId = request.id,
                    createdAt = now,
                    lastUpdatedAt = now,
                )
            book.put(managed)
        }
        children.hold(parent.id, request.children, request)
        exposure.register(exposureEntryRequest(request.parent))
        recovered += parent
    }

    /** Re-creates a pre-fill bracket in the same shape [BracketSubmission] gave it on this venue. */
    fun restoreBracket(
        request: OrderRequest.Bracket,
        recovered: MutableList<ManagedOrder>,
    ) {
        val caps = broker.capabilitiesFor(request.symbol)
        val isEngineManagedStop = request.stopLoss !is StopLossSpec.Fixed
        val needsFillAnchor =
            (request.stopLossAst != null && request.stopLossAst !is ChildAt) ||
                (request.takeProfitAst != null && request.takeProfitAst !is ChildAt)
        val canAttach =
            OrderTypeCapability.BRACKET in caps && OrderTypeCapability.POSITION_MODIFY in caps
        val now = clock.now()

        when {
            canAttach -> {
                val attached = request.copy(id = request.entry.id)
                val managed =
                    ManagedOrder(
                        id = attached.id,
                        request = attached,
                        state = OrderState.WORKING,
                        createdAt = now,
                        lastUpdatedAt = now,
                    )
                book.put(managed)
                brackets.restoredAttachedEntries += attached.id
                brackets.preFill[attached.id] = request
                // Expression-anchored exits are built from the fill. So is an engine-managed
                // stop restored before the venue has quoted its symbol: there is no price to
                // anchor it on yet, and failing the deploy here would be retried forever
                // because the quote only starts flowing once the strategy is deployed.
                val anchorAtFill =
                    needsFillAnchor || (isEngineManagedStop && bracketExits.entryEstimateOrNull(request) == null)
                if (anchorAtFill) brackets.fillAnchoredAttached[attached.id] = request
                val restoredStop = if (anchorAtFill) null else bracketExits.managedStop(request, now)
                restoredStop?.let { stop ->
                    ops.track(
                        ManagedOrder(
                            id = stop.id,
                            request = stop,
                            state = OrderState.CREATED,
                            parentClientOrderId = request.id,
                            createdAt = now,
                            lastUpdatedAt = now,
                        ),
                    )
                    children.hold(attached.id, listOf(stop))
                }
                exposure.register(attached)
                recovered += managed
            }
            !isEngineManagedStop && !needsFillAnchor && OrderTypeCapability.BRACKET in caps -> {
                val managed =
                    ManagedOrder(
                        id = request.id,
                        request = request,
                        state = OrderState.WORKING,
                        createdAt = now,
                        lastUpdatedAt = now,
                    )
                book.put(managed)
                exposure.register(request)
                recovered += managed
            }
            else -> {
                val entry = request.entry.withStrategyId(request.strategyId)
                val managed =
                    ManagedOrder(
                        id = entry.id,
                        request = entry,
                        state = OrderState.WORKING,
                        createdAt = now,
                        lastUpdatedAt = now,
                    )
                book.put(managed)
                brackets.preFill[entry.id] = request
                // A Market entry restored before the venue has quoted its symbol has no price to
                // anchor the exits on; place them from the actual fill instead of failing the
                // whole deploy (which the daemon would retry forever, quote or no quote).
                val entryEstimate = if (needsFillAnchor) null else bracketExits.entryEstimateOrNull(request)
                if (entryEstimate == null) {
                    brackets.fillAnchoredFallback[entry.id] = request
                } else {
                    children.hold(entry.id, listOf(bracketExits.exitOco(request, entryEstimate, request.quantity)))
                }
                exposure.register(entry)
                recovered += managed
            }
        }
    }
}
