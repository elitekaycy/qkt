package com.qkt.broker.bybit

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType

object BybitOrderTranslator {
    fun toCreateBody(request: OrderRequest): String {
        val parsed = BybitSymbol.parse(request.symbol)
        val side = if (request.side == Side.BUY) "Buy" else "Sell"
        val tif = mapTif(request.timeInForce)

        val (orderType, priceField, triggerFields) =
            when (request) {
                is OrderRequest.Market -> Triple<String, String?, TriggerSet?>("Market", null, null)
                is OrderRequest.Limit ->
                    Triple<String, String?, TriggerSet?>("Limit", request.limitPrice.toPlainString(), null)
                is OrderRequest.Stop ->
                    Triple<String, String?, TriggerSet?>(
                        "Market",
                        null,
                        TriggerSet(request.stopPrice.toPlainString(), stopDirection(request.side)),
                    )
                is OrderRequest.StopLimit ->
                    Triple<String, String?, TriggerSet?>(
                        "Limit",
                        request.limitPrice.toPlainString(),
                        TriggerSet(request.stopPrice.toPlainString(), stopDirection(request.side)),
                    )
                is OrderRequest.IfTouched -> {
                    val ot = if (request.onTrigger == TriggerType.MARKET) "Market" else "Limit"
                    val limitField =
                        if (request.onTrigger == TriggerType.LIMIT) request.limitPrice!!.toPlainString() else null
                    Triple<String, String?, TriggerSet?>(
                        ot,
                        limitField,
                        TriggerSet(request.triggerPrice.toPlainString(), ifTouchedDirection(request.side)),
                    )
                }
                else ->
                    error(
                        "BybitOrderTranslator does not handle ${request::class.simpleName} (Tier 3 belongs in OrderManager)",
                    )
            }

        val sb = StringBuilder("{")
        sb.append("\"category\":\"${parsed.category}\",")
        sb.append("\"symbol\":\"${parsed.bare}\",")
        sb.append("\"side\":\"$side\",")
        sb.append("\"orderType\":\"$orderType\",")
        sb.append("\"qty\":\"${request.quantity.toPlainString()}\",")
        if (priceField != null) sb.append("\"price\":\"$priceField\",")
        if (triggerFields != null) {
            sb.append("\"triggerPrice\":\"${triggerFields.price}\",")
            sb.append("\"triggerDirection\":${triggerFields.direction},")
        }
        sb.append("\"timeInForce\":\"$tif\",")
        sb.append("\"orderLinkId\":\"${request.id}\"")
        sb.append("}")
        return sb.toString()
    }

    fun toCancelBody(
        symbol: String,
        orderLinkId: String,
    ): String {
        val parsed = BybitSymbol.parse(symbol)
        return """{"category":"${parsed.category}","symbol":"${parsed.bare}","orderLinkId":"$orderLinkId"}"""
    }

    private fun mapTif(tif: TimeInForce): String =
        when (tif) {
            TimeInForce.GTC, TimeInForce.DAY -> "GTC"
            TimeInForce.IOC -> "IOC"
            TimeInForce.FOK -> "FOK"
        }

    private fun stopDirection(side: Side): Int = if (side == Side.BUY) 1 else 2

    private fun ifTouchedDirection(side: Side): Int = if (side == Side.BUY) 2 else 1

    private data class TriggerSet(
        val price: String,
        val direction: Int,
    )
}
