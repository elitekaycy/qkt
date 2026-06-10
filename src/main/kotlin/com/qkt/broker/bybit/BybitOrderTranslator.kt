package com.qkt.broker.bybit

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Converts qkt [OrderRequest]s into Bybit's `POST /v5/order/create` JSON body. */
object BybitOrderTranslator {
    fun toCreateBody(
        request: OrderRequest,
        reduceOnly: Boolean = false,
    ): String {
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
        if (parsed.category == "linear") {
            sb.append(",\"positionIdx\":0")
            if (reduceOnly) sb.append(",\"reduceOnly\":true")
        }
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
            TimeInForce.GTC, TimeInForce.DAY, TimeInForce.GTD -> "GTC"
            TimeInForce.IOC -> "IOC"
            TimeInForce.FOK -> "FOK"
        }

    private fun stopDirection(side: Side): Int = if (side == Side.BUY) 1 else 2

    private fun ifTouchedDirection(side: Side): Int = if (side == Side.BUY) 2 else 1

    private data class TriggerSet(
        val price: String,
        val direction: Int,
    )

    data class ParsedOpenOrder(
        val clientOrderId: String,
        val brokerOrderId: String?,
        val bareSymbol: String,
        val side: Side,
        val status: String,
    )

    data class ParsedExecution(
        val execId: String,
        val clientOrderId: String,
        val brokerOrderId: String?,
        val bareSymbol: String,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
        /** Venue execution fee for this slice — positive = charge, negative = maker rebate. */
        val fee: BigDecimal = Money.ZERO,
    )

    fun parseOpenOrder(json: JsonObject): ParsedOpenOrder {
        val sideStr = json["side"]?.jsonPrimitive?.content ?: error("missing side: $json")
        return ParsedOpenOrder(
            clientOrderId = json["orderLinkId"]?.jsonPrimitive?.content ?: error("missing orderLinkId: $json"),
            brokerOrderId = json["orderId"]?.jsonPrimitive?.content,
            bareSymbol = json["symbol"]?.jsonPrimitive?.content ?: error("missing symbol: $json"),
            side = if (sideStr == "Buy") Side.BUY else Side.SELL,
            status = json["orderStatus"]?.jsonPrimitive?.content ?: error("missing orderStatus: $json"),
        )
    }

    fun parseExecution(json: JsonObject): ParsedExecution {
        val sideStr = json["side"]?.jsonPrimitive?.content ?: error("missing side: $json")
        return ParsedExecution(
            execId = json["execId"]?.jsonPrimitive?.content ?: error("missing execId: $json"),
            clientOrderId = json["orderLinkId"]?.jsonPrimitive?.content ?: error("missing orderLinkId: $json"),
            brokerOrderId = json["orderId"]?.jsonPrimitive?.content,
            bareSymbol = json["symbol"]?.jsonPrimitive?.content ?: error("missing symbol: $json"),
            side = if (sideStr == "Buy") Side.BUY else Side.SELL,
            price =
                json["execPrice"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toBigDecimal()
                    ?.setScale(Money.SCALE, Money.ROUNDING)
                    ?: error("missing execPrice: $json"),
            quantity =
                json["execQty"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toBigDecimal()
                    ?.setScale(Money.SCALE, Money.ROUNDING)
                    ?: error("missing execQty: $json"),
            // Bybit reports execFee positive = charge (maker rebates arrive negative).
            fee =
                json["execFee"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toBigDecimalOrNull()
                    ?.setScale(Money.SCALE, Money.ROUNDING)
                    ?: Money.ZERO,
        )
    }
}
