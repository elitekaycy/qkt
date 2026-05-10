package com.qkt.broker.bybit

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses Bybit's `wallet-balance` REST response into `currency → balance` map. */
object BybitBalanceTranslator {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseWalletBalance(response: String): Map<String, BigDecimal> {
        val root = json.parseToJsonElement(response).jsonObject
        val list = root["result"]?.jsonObject?.get("list")?.jsonArray ?: return emptyMap()
        if (list.isEmpty()) return emptyMap()

        val coins = list.first().jsonObject["coin"]?.jsonArray ?: return emptyMap()
        val out = mutableMapOf<String, BigDecimal>()
        for (entry in coins) {
            val obj = entry.jsonObject
            val coin = obj["coin"]?.jsonPrimitive?.content ?: continue
            val rawBalance = obj["walletBalance"]?.jsonPrimitive?.content ?: continue
            if (rawBalance.isBlank()) continue
            out[coin] = BigDecimal(rawBalance)
        }
        return out
    }
}
