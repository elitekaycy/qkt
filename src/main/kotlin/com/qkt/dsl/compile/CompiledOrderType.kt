package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal

/** A compiled order-type clause: the request builder and the entry-price estimate used for sizing. */
data class CompiledOrderType(
    val buildRequest: BuildRequest,
    val entryPrice: EntryPriceRef,
)

/** The price an order is expected to enter at, or null while an operand is undefined. */
fun interface EntryPriceRef {
    fun evaluate(ec: EvalContext): BigDecimal?
}

/** Builds the [OrderRequest] for an order type, or null while a price operand is undefined. */
fun interface BuildRequest {
    fun evaluate(
        ec: EvalContext,
        id: String,
        symbol: String,
        side: Side,
        qty: BigDecimal,
        tif: TimeInForce,
        strategyId: String,
        ts: Long,
    ): OrderRequest?
}
