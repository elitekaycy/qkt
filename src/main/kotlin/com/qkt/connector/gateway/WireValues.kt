package com.qkt.connector.gateway

import com.qkt.accounting.CostKind
import com.qkt.accounting.MoneyAmount
import com.qkt.accounting.VenueCost
import com.qkt.common.Side
import java.math.BigDecimal

/** VGP v1 values read exactly: decimals from their text, sides and cost kinds by their wire names, anything else refused. */
internal object WireValues {
    fun costsOf(
        costs: List<WireCost>,
        at: Long,
    ): List<VenueCost> =
        costs.map { VenueCost(kindOf(it.kind), MoneyAmount(decimal(it.amount, "cost"), it.currency), at) }

    fun sideOf(side: String): Side =
        when (side) {
            "buy" -> Side.BUY
            "sell" -> Side.SELL
            else -> throw GatewayProtocolException("side '$side'")
        }

    fun kindOf(kind: String): CostKind =
        when (kind) {
            "commission" -> CostKind.COMMISSION
            // A delivery fee is an exchange fee, as the backtest option venue reports it.
            "exchange_fee", "delivery_fee" -> CostKind.EXCHANGE_FEE
            "funding" -> CostKind.FUNDING
            "swap" -> CostKind.SWAP
            else -> throw GatewayProtocolException("cost kind '$kind'")
        }

    fun decimal(
        text: String,
        field: String,
    ): BigDecimal = text.toBigDecimalOrNull() ?: throw GatewayProtocolException("$field '$text' is not a decimal")
}
