package com.qkt.connector.bybit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Bybit's own v5 response examples, as payloads for the Bybit brokers. Each starts from the documented
 * record verbatim; only the fields a scenario needs (named per function) are substituted.
 */
object BybitDocPayloads {
    /** The `data[0]` record of docs/v5/websocket/private/execution (a linear market Sell of 0.5 BTCUSDT). */
    private const val WS_EXECUTION =
        """{"category":"linear","symbol":"BTCUSDT","closedSize":"0.5","execFee":"26.3725275",""" +
            """"execId":"0ab1bdf7-4219-438b-b30a-32ec863018f7","execPrice":"95900.1","execQty":"0.5",""" +
            """"execType":"Trade","execValue":"47950.05","feeRate":"0.00055","tradeIv":"","markIv":"",""" +
            """"blockTradeId":"","markPrice":"95901.48","indexPrice":"","underlyingPrice":"","leavesQty":"0",""" +
            """"orderId":"9aac161b-8ed6-450d-9cab-c5cc67c21784","orderLinkId":"","orderPrice":"94942.5",""" +
            """"orderQty":"0.5","orderType":"Market","stopOrderType":"UNKNOWN","side":"Sell",""" +
            """"execTime":"1746270400353","isLeverage":"0","isMaker":false,"seq":140612148849382,""" +
            """"marketUnit":"","execPnl":"0.05","createType":"CreateByUser","extraFees":[{"feeCoin":"USDT",""" +
            """"feeType":"GST","subFeeType":"IND_GST","feeRate":"0.0000675","fee":"0.006403779"}],""" +
            """"feeCurrency":"USDT"}"""

    /** The `data[0]` record of docs/v5/websocket/private/order (an option order there; made linear here). */
    private const val WS_ORDER =
        """{"symbol":"ETH-30DEC22-1400-C","orderId":"5cf98598-39a7-459e-97bf-76ca765ee020","side":"Sell",""" +
            """"orderType":"Market","cancelType":"UNKNOWN","price":"72.5","qty":"1","orderIv":"",""" +
            """"timeInForce":"IOC","orderStatus":"Filled","orderLinkId":"","lastPriceOnCreated":"",""" +
            """"reduceOnly":false,"leavesQty":"","leavesValue":"","cumExecQty":"1","cumExecValue":"75",""" +
            """"avgPrice":"75","blockTradeId":"","positionIdx":0,"cumExecFee":"0.358635","closedPnl":"0",""" +
            """"createdTime":"1672364262444","updatedTime":"1672364262457","rejectReason":"EC_NoError",""" +
            """"stopOrderType":"","tpslMode":"","triggerPrice":"","takeProfit":"","stopLoss":"",""" +
            """"tpTriggerBy":"","slTriggerBy":"","tpLimitPrice":"","slLimitPrice":"","triggerDirection":0,""" +
            """"triggerBy":"","closeOnTrigger":false,"category":"option","placeType":"price","smpType":"None",""" +
            """"smpGroup":"0","smpOrderId":"","feeCurrency":"","cumFeeDetail":{"MNT":"0.00242968"},""" +
            """"rpiTakerAccess":false,"rpiMatchedQty":"0"}"""

    /** The `result.list[0]` record of docs/v5/order/execution (`/v5/execution/list`: a Buy of 0.1 ETHPERP). */
    const val REST_EXECUTION =
        """{"symbol":"ETHPERP","orderType":"Market","underlyingPrice":"","orderLinkId":"","side":"Buy",""" +
            """"indexPrice":"","orderId":"8c065341-7b52-4ca9-ac2c-37e31ac55c94","stopOrderType":"UNKNOWN",""" +
            """"leavesQty":"0","execTime":"1672282722429","feeCurrency":"","isMaker":false,"execFee":"0.071409",""" +
            """"feeRate":"0.0006","execId":"e0cbe81d-0f18-5866-9415-cf319b5dab3b","tradeIv":"",""" +
            """"blockTradeId":"","markPrice":"1183.54","execPrice":"1190.15","markIv":"","orderQty":"0.1",""" +
            """"orderPrice":"1236.9","execValue":"119.015","execType":"Trade","execQty":"0.1","closedSize":"",""" +
            """"extraFees":"","seq":4688002127}"""

    const val WS_ORDER_ID = "9aac161b-8ed6-450d-9cab-c5cc67c21784"

    /**
     * The documented execution as a slice of [execQty] with [leavesQty] left, id [execId], for [orderLinkId];
     * the fee is the documented `feeRate` (0.00055) on the slice's value, as Bybit computes it. [category]
     * replaces the documented `linear`.
     */
    fun wsExecution(
        orderLinkId: String,
        execId: String = "0ab1bdf7-4219-438b-b30a-32ec863018f7",
        execQty: String = "0.5",
        leavesQty: String = "0",
        execFee: String = "26.3725275",
        category: String = "linear",
    ): String =
        WS_EXECUTION
            .replace("\"category\":\"linear\"", "\"category\":\"$category\"")
            .replace("\"orderLinkId\":\"\"", "\"orderLinkId\":\"$orderLinkId\"")
            .replace("\"execId\":\"0ab1bdf7-4219-438b-b30a-32ec863018f7\"", "\"execId\":\"$execId\"")
            .replace("\"execQty\":\"0.5\"", "\"execQty\":\"$execQty\"")
            .replace("\"leavesQty\":\"0\"", "\"leavesQty\":\"$leavesQty\"")
            .replace("\"execFee\":\"26.3725275\"", "\"execFee\":\"$execFee\"")

    /** The documented order update as [category] order [orderLinkId] on BTCUSDT, at [status] with [cumExecQty]. */
    fun wsOrder(
        orderLinkId: String,
        status: String,
        cumExecQty: String,
        category: String = "linear",
    ): String =
        WS_ORDER
            .replace("\"symbol\":\"ETH-30DEC22-1400-C\"", "\"symbol\":\"BTCUSDT\"")
            .replace("\"orderId\":\"5cf98598-39a7-459e-97bf-76ca765ee020\"", "\"orderId\":\"$WS_ORDER_ID\"")
            .replace("\"category\":\"option\"", "\"category\":\"$category\"")
            .replace("\"orderLinkId\":\"\"", "\"orderLinkId\":\"$orderLinkId\"")
            .replace("\"orderStatus\":\"Filled\"", "\"orderStatus\":\"$status\"")
            .replace("\"qty\":\"1\"", "\"qty\":\"0.5\"")
            .replace("\"cumExecQty\":\"1\"", "\"cumExecQty\":\"$cumExecQty\"")

    /** [REST_EXECUTION] for [orderLinkId]. */
    fun restExecution(orderLinkId: String): String =
        REST_EXECUTION.replace("\"orderLinkId\":\"\"", "\"orderLinkId\":\"$orderLinkId\"")

    /** A private-stream frame of [topic] carrying [entries]. */
    fun frame(
        topic: String,
        vararg entries: String,
    ): JsonObject = Json.parseToJsonElement("""{"topic":"$topic","data":[${entries.joinToString(",")}]}""").jsonObject

    /** A `/v5/execution/list` page of [entries]. */
    fun page(vararg entries: String): String =
        """{"retCode":0,"retMsg":"OK","result":{"list":[${entries.joinToString(",")}],"nextPageCursor":""}}"""
}
