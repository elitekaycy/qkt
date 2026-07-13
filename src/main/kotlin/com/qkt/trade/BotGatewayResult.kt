package com.qkt.trade

import com.qkt.broker.mt5.MT5OrderResponse
import com.qkt.broker.mt5.isOrderSuccessful
import java.math.BigDecimal

/**
 * Outcome of a one-shot venue mutation (place, close, modify, cancel). [ok] is true
 * only when the venue acknowledged with a success retcode; [error] carries the
 * venue's reason otherwise. [ticket] is the venue order/position ticket and [deal]
 * the executed deal id (0 when the shape produced none, e.g. a pending placement).
 */
data class BotPlaceResult(
    val ok: Boolean,
    val retcode: Int,
    val ticket: Long,
    val deal: Long,
    val price: BigDecimal,
    val error: String?,
)

/** Maps the MT5 wire response onto [BotPlaceResult], surfacing the venue comment on failure. */
fun MT5OrderResponse.toBotResult(): BotPlaceResult {
    val ok = errorMessage == null && isOrderSuccessful(result.retcode)
    return BotPlaceResult(
        ok = ok,
        retcode = result.retcode,
        ticket = result.order,
        deal = result.deal,
        price = result.price,
        error = if (ok) null else errorMessage ?: result.comment.ifBlank { "retcode ${result.retcode}" },
    )
}
