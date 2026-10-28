package com.qkt.broker

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
