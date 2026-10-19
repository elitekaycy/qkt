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
