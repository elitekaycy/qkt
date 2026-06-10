package com.qkt.marketdata

import java.math.BigDecimal

/**
 * The price a BUY executes at — the ask, falling back to the tick's single price
 * (mid for quote-driven feeds) when the feed carries no quote depth. Trigger checks
 * must use this side: MT5 fires BUY_STOP on the ask, so an engine or simulator that
 * triggers on mid is systematically ~half a spread optimistic per trigger event.
 */
fun Tick.buyExecPrice(): BigDecimal = ask ?: price

/** The price a SELL executes at — the bid; see [buyExecPrice]. */
fun Tick.sellExecPrice(): BigDecimal = bid ?: price
