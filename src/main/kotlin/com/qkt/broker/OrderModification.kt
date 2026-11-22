package com.qkt.broker

import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * Fields a strategy or operator can change on a working order.
 *
 * Only non-null fields are applied; brokers ignore unset ones.
 */
data class OrderModification(
    val newQuantity: BigDecimal? = null,
    val newLimitPrice: BigDecimal? = null,
    val newStopPrice: BigDecimal? = null,
)

/**
 * This order with [changes]' price levels: a limit's limit, a stop's stop, a stop-limit's both. A level the order does
 * not have, and the quantity, are left as they are.
 */
fun OrderRequest.withLevels(changes: OrderModification): OrderRequest =
    when (this) {
        is OrderRequest.Limit -> copy(limitPrice = changes.newLimitPrice ?: limitPrice)
        is OrderRequest.Stop -> copy(stopPrice = changes.newStopPrice ?: stopPrice)
        is OrderRequest.StopLimit ->
            copy(stopPrice = changes.newStopPrice ?: stopPrice, limitPrice = changes.newLimitPrice ?: limitPrice)
        else -> this
    }
