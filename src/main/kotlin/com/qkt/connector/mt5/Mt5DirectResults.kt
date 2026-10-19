package com.qkt.connector.mt5

import com.qkt.connectivity.DirectResult

/** The MT5 wire response as a [DirectResult], with the venue's comment on failure. */
internal fun MT5OrderResponse.toDirect(): DirectResult {
    val ok = errorMessage == null && isOrderSuccessful(result.retcode)
    return DirectResult(
        ok = ok,
        retcode = result.retcode,
        ticket = result.order.toString(),
        deal = result.deal.toString(),
        price = result.price,
        error = if (ok) null else errorMessage ?: result.comment.ifBlank { "retcode ${result.retcode}" },
    )
}

internal fun MT5Position.toDirect(): com.qkt.connectivity.DirectPosition =
    com.qkt.connectivity.DirectPosition(
        ticket = ticket.toString(),
        symbol = symbol,
        side = if (type == 0) "BUY" else "SELL",
        lots = volume,
        entry = priceOpen,
        current = priceCurrent,
        sl = sl,
        tp = tp,
        profit = profit,
        swap = swap,
        openedAtMs = openTime,
        comment = comment,
    )

internal fun MT5Deal.toDirect(): com.qkt.connectivity.DirectDeal =
    com.qkt.connectivity.DirectDeal(
        dealTicket = ticket.toString(),
        positionTicket = positionTicket.toString(),
        symbol = symbol,
        side = if (type == 0) "BUY" else "SELL",
        entry = if (entry == 0) "IN" else "OUT",
        lots = volume,
        price = price,
        profit = profit,
        commission = commission,
        swap = swap,
        fee = fee,
        timeMs = timeMs,
        comment = comment,
    )
