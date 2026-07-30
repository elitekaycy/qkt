package com.qkt.broker.bybit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BybitOrderResolution(
    val brokerOrderId: String?,
    val status: String,
)

internal fun requireBybitOk(
    response: String,
    operation: String,
    json: Json,
): JsonObject {
    val tree =
        runCatching { json.parseToJsonElement(response).jsonObject }
            .getOrElse { throw IllegalStateException("$operation returned invalid JSON", it) }
    val retCode =
        tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw IllegalStateException("$operation response omitted retCode")
    if (retCode != 0) {
        val retMsg = tree["retMsg"]?.jsonPrimitive?.content.orEmpty()
        throw BybitApiException(retCode, "$operation: $retMsg")
    }
    return tree
}

internal fun resolveBybitOrder(
    transport: BybitTransport,
    category: String,
    clientOrderId: String,
    json: Json,
): BybitOrderResolution? {
    val response =
        transport.getSigned(
            "/v5/order/realtime",
            mapOf(
                "category" to category,
                "orderLinkId" to clientOrderId,
                "openOnly" to "0",
                "limit" to "1",
            ),
        )
    val tree = requireBybitOk(response, "order outcome lookup", json)
    val list =
        tree["result"]?.jsonObject?.get("list")?.jsonArray
            ?: throw IllegalStateException("order outcome lookup response omitted result.list")
    val order =
        list
            .firstOrNull {
                it.jsonObject["orderLinkId"]?.jsonPrimitive?.content == clientOrderId
            }?.jsonObject ?: return null
    return BybitOrderResolution(
        brokerOrderId = order["orderId"]?.jsonPrimitive?.content,
        status = order["orderStatus"]?.jsonPrimitive?.content.orEmpty(),
    )
}
