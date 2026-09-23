package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.execution.OrderRequest

/**
 * Registers the `STACK_AT` tiers of a just-submitted parent order, keyed by the client order id
 * its entry fill will carry, so the runtime arms a [StackEngine] when that fill arrives. With an
 * `EXIT AFTER` hold, every stack leg carries [exitAfterMs] too, and the parent's timed close
 * ([parentExitId]`-close`) is watched so no tier fires after the parent has timed out.
 */
internal fun registerPendingStack(
    pendingStacks: PendingStacks,
    finalRequest: OrderRequest,
    symbol: String,
    side: Side,
    stackAtTiers: List<CompiledStackTier>,
    exitAfterMs: Long? = null,
    parentExitId: String? = null,
) {
    pendingStacks.register(
        PendingStack(
            parentClientOrderId = parentClientOrderIdFor(finalRequest),
            symbol = symbol,
            side = side,
            tiers = stackAtTiers,
            closeWatchIds = closeWatchIdsFor(finalRequest) + listOfNotNull(parentExitId?.let { "$it-close" }),
            exitAfterMs = exitAfterMs,
        ),
    )
}

/**
 * The clientOrderId the broker echoes back on [com.qkt.events.BrokerEvent.OrderFilled]
 * for the parent leg's primary entry. For a plain Market submit it's the request id;
 * for a Bracket parent the broker fills the inner entry, so it's [OrderRequest.Bracket.entry.id].
 */
internal fun parentClientOrderIdFor(request: OrderRequest): String =
    when (request) {
        is OrderRequest.Bracket -> request.entry.id
        else -> request.id
    }

/**
 * Predicted clientOrderIds whose fill signals that the parent leg has closed. For
 * Bracket parents this matches the deterministic naming in
 * [com.qkt.app.OrderManager.submitBracketFallback]: `<bracket-id>-tp` and `<bracket-id>-sl`.
 *
 * Phase 27 limitation: this covers the paper/backtest path where bracket-fallback
 * controls the child ids. Native broker brackets (e.g. MT5) and strategy-initiated
 * manual closes rely on leg-aware fill routing (a separate task).
 */
internal fun closeWatchIdsFor(request: OrderRequest): Set<String> =
    when (request) {
        is OrderRequest.Bracket -> setOf("${request.id}-tp", "${request.id}-sl")
        else -> emptySet()
    }
