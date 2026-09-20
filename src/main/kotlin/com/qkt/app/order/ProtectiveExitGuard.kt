package com.qkt.app.order

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.isTerminal
import java.math.BigDecimal
import org.slf4j.Logger

/**
 * Keeps engine-managed protective exits (`-sl` / `-tp`) reduce-only against the strategy's net
 * position (#1069): exits whose position was consumed are retired, and an exit fill that left
 * the position on its own side raises an operator alert. An exit linked to its own leg
 * ([LegIntent.Close]) is judged by that leg, not the net, so it is exempt. A null
 * [strategyNetQty] disables both.
 */
internal class ProtectiveExitGuard(
    private val book: OrderBook,
    private val ops: OrderOps,
    private val strategyNetQty: ((strategyId: String, symbol: String) -> BigDecimal)?,
    private val log: Logger,
) {
    /**
     * Reduce-only tripwire (#1069): an engine-managed protective exit may only shrink the
     * position its bracket opened. After an exit fill the net position must not sit on the
     * fill's own side — long after a BUY exit (or short after a SELL exit) means the "exit"
     * added exposure. [retireStale] prevents the known stale-exit path; this detector
     * refuses to let ANY future path fail silently: it raises the operator protection alert
     * (live: telegram/log; backtest: report + log) the moment the invariant breaks.
     */
    fun onExitFilled(e: BrokerEvent.OrderFilled) {
        if (!e.clientOrderId.endsWith("-sl") && !e.clientOrderId.endsWith("-tp")) return
        if (isLegLinked(e.clientOrderId)) return
        val netQty = strategyNetQty?.invoke(e.strategyId, e.symbol) ?: return
        val landedOnOwnSide =
            (e.side == Side.BUY && netQty.signum() > 0) ||
                (e.side == Side.SELL && netQty.signum() < 0)
        if (!landedOnOwnSide) return
        val message =
            "REDUCE-ONLY VIOLATION: protective exit ${e.clientOrderId} filled ${e.side} " +
                "${e.quantity} ${e.symbol} but net position is now $netQty — an exit added exposure"
        log.error(message)
        ops.reportProtectionFailure(e.strategyId, message)
    }

    /**
     * A protective exit exists to REDUCE the position its bracket opened. When a netting fill
     * consumes that position (reversal, or a flatten), the venue drops the position's SL/TP with
     * it — an engine-managed resting exit must be retired the same way, or it later fires as a
     * naked opposite-direction entry with no protection of its own (#1069). Stale means: the
     * exit's side would INCREASE the current net strategy position (any exit is stale when flat).
     * A partial reduce that keeps the sign leaves exits alone — reducing them is venue-faithful
     * resizing, tracked separately.
     */
    fun retireStale(
        strategyId: String,
        symbol: String,
    ) {
        val netQty = strategyNetQty?.invoke(strategyId, symbol) ?: return
        val staleSide =
            when {
                netQty.signum() > 0 -> Side.BUY
                netQty.signum() < 0 -> Side.SELL
                else -> null // flat: every resting exit is stale
            }
        val stale =
            book.orders.entries.filter { (id, managed) ->
                !managed.state.isTerminal &&
                    (id.endsWith("-sl") || id.endsWith("-tp")) &&
                    managed.request.strategyId == strategyId &&
                    managed.request.symbol == symbol &&
                    (staleSide == null || managed.request.side == staleSide) &&
                    !isLegLinked(id)
            }
        for ((id, managed) in stale) {
            val request = managed.request
            log.warn(
                "retiring stale protective exit {} {} {} — its position was consumed (net {} {})",
                id,
                request.side,
                request.quantity,
                netQty,
                symbol,
            )
            ops.cancel(id)
        }
    }

    /**
     * An exit carrying a [LegIntent.Close] closes exactly its own leg, so the net-based stale
     * sweep and reduce-only tripwire must not judge it: under a hedging book a short leg's BUY
     * stop while net-long is a legitimate exit (#1071).
     */
    private fun isLegLinked(clientOrderId: String): Boolean = book[clientOrderId]?.request?.legIntent is LegIntent.Close
}
