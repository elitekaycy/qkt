package com.qkt.marketdata.live.bybit

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed class BybitPublicFrame {
    data class Tickers(
        val symbol: String,
        val bid: BigDecimal,
        val ask: BigDecimal,
        val last: BigDecimal,
    ) : BybitPublicFrame()

    data class Trade(
        val symbol: String,
        val price: BigDecimal,
        val volume: BigDecimal,
        val brokerTimeMs: Long,
    ) : BybitPublicFrame()

    data class SubscribeAck(
        val success: Boolean,
        val message: String,
    ) : BybitPublicFrame()

    data class Unknown(
        val raw: String,
    ) : BybitPublicFrame()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): BybitPublicFrame {
            val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return Unknown(text)
            if (obj["op"] != null) {
                return SubscribeAck(
                    success = obj["success"]?.jsonPrimitive?.boolean ?: false,
                    message = obj["ret_msg"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
            val topic = obj["topic"]?.jsonPrimitive?.content ?: return Unknown(text)
            return when {
                topic.startsWith("tickers.") -> parseTickers(topic, obj)
                topic.startsWith("publicTrade.") -> parseTrade(topic, obj)
                else -> Unknown(text)
            }
        }

        private fun parseTickers(
            topic: String,
            obj: JsonObject,
        ): BybitPublicFrame {
            val data = obj["data"]?.jsonObject ?: return Unknown(obj.toString())
            return Tickers(
                symbol = topic.removePrefix("tickers."),
                bid = data["bid1Price"]!!.jsonPrimitive.content.toBigDecimal(),
                ask = data["ask1Price"]!!.jsonPrimitive.content.toBigDecimal(),
                last = data["lastPrice"]!!.jsonPrimitive.content.toBigDecimal(),
            )
        }

        private fun parseTrade(
            topic: String,
            obj: JsonObject,
        ): BybitPublicFrame {
            val arr = obj["data"]?.jsonArray ?: return Unknown(obj.toString())
            val first = arr.firstOrNull()?.jsonObject ?: return Unknown(obj.toString())
            return Trade(
                symbol = topic.removePrefix("publicTrade."),
                price = first["p"]!!.jsonPrimitive.content.toBigDecimal(),
                volume = first["v"]!!.jsonPrimitive.content.toBigDecimal(),
                brokerTimeMs = first["T"]!!.jsonPrimitive.content.toLong(),
            )
        }
    }
}
