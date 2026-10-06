package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Shapes the signals of a rule's retry fire after one of its exits ended unfilled (#1359): a retry
 * only reduces what the strategy still holds. A market order against the position is cut to the
 * quantity still held, so a part-filled exit resends its remainder and never reverses; a close by
 * leg or ticket is kept, since it was sized from that leg; a structure close and every cancel stay.
 * Anything that would add exposure is dropped — entries are never retried.
 */
internal object ExitRetryClamp {
    fun clamp(
        signals: List<Signal>,
        positions: StrategyPositionView,
    ): List<Signal> = signals.mapNotNull { reduceOnly(it, positions) }

    private fun reduceOnly(
        signal: Signal,
        positions: StrategyPositionView,
    ): Signal? =
        when (signal) {
            is Signal.Buy ->
                held(
                    positions,
                    signal.symbol,
                    buying = true,
                )?.let { signal.copy(size = signal.size.min(it)) }
            is Signal.Sell ->
                held(
                    positions,
                    signal.symbol,
                    buying = false,
                )?.let { signal.copy(size = signal.size.min(it)) }
            is Signal.Submit -> {
                val request = signal.request
                when {
                    request !is OrderRequest.Market -> null
                    request.closesLegId != null || request.closesTicket != null -> signal
                    else ->
                        held(positions, request.symbol, buying = request.side == Side.BUY)
                            ?.let { signal.copy(request = request.copy(quantity = request.quantity.min(it))) }
                }
            }
            is Signal.SubmitGroup -> signal.takeIf { it.closes != null }
            is Signal.ArmLatch -> null
            is Signal.CancelPendingForSymbol, is Signal.Suppressed -> signal
        }

    /** The quantity an order on this side would reduce: the held size against it, or null when none. */
    private fun held(
        positions: StrategyPositionView,
        symbol: String,
        buying: Boolean,
    ): BigDecimal? {
        val qty = positions.positionFor(symbol)?.quantity ?: return null
        val against = if (buying) qty.negate() else qty
        return against.takeIf { it.signum() > 0 }
    }
}
