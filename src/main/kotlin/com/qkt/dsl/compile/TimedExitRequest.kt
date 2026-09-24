package com.qkt.dsl.compile

import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.strategy.Signal
import java.time.Instant

/**
 * Wraps [target] so the engine closes its leg at market [holdMs] after the entry fills — the
 * compiled form of `EXIT AFTER`. The deadline is armed on the fill and checked per tick in
 * both backtest and live, so it does not wait for a bar close.
 */
internal fun timedExit(
    id: String,
    target: OrderRequest,
    holdMs: Long,
    timestamp: Long,
): OrderRequest.TimeExit =
    OrderRequest.TimeExit(
        id = id,
        symbol = target.symbol,
        side = target.side,
        quantity = target.quantity,
        target = target,
        deadline = Instant.ofEpochMilli(timestamp + holdMs),
        onExpiry = ExpiryAction.CLOSE_AT_MARKET,
        timeInForce = target.timeInForce,
        timestamp = timestamp,
        strategyId = target.strategyId,
        holdMs = holdMs,
    )

/** Wraps a stack leg's bracket in the parent's `EXIT AFTER` exit, timed from the leg's own fill. */
internal fun Signal.withExitAfter(holdMs: Long?): Signal {
    if (holdMs == null || this !is Signal.Submit) return this
    val req = request as? OrderRequest.Bracket ?: return this
    return copy(request = timedExit("${req.id}-exit", req, holdMs, req.timestamp))
}
