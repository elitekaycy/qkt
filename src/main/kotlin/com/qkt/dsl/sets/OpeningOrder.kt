package com.qkt.dsl.sets

import com.qkt.common.Side
import com.qkt.strategy.Signal

/**
 * The symbol and side of an order [this] signal sends (`BUY`, `SELL` or a typed order), or null for a signal that
 * sends none (a cancel, an arm, a log).
 */
internal fun Signal.orderSide(): Pair<String, Side>? =
    when (this) {
        is Signal.Buy -> symbol to Side.BUY
        is Signal.Sell -> symbol to Side.SELL
        is Signal.Submit -> request.symbol to request.side
        else -> null
    }

/** Whether an order on [side] opens or adds against a holding of sign [held] (-1 short, 0 flat, 1 long). */
internal fun opensOrAdds(
    side: Side,
    held: Int,
): Boolean = held == 0 || (held > 0) == (side == Side.BUY)
