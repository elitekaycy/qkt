package com.qkt.connector.gateway

import com.qkt.observe.insights.InsightsEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** How [ConnectorEnvelopeShapeTest] reads an envelope's shape: its key paths and the values it states. */
internal object EnvelopeShapes {
    /** Every key path of [payload], lists walked into, null values kept: the shape the translator emits. */
    fun paths(
        payload: Map<String, Any?>,
        prefix: String = "",
    ): Set<String> =
        payload
            .flatMap { (k, v) ->
                val path = "$prefix$k"
                when (v) {
                    is Map<*, *> -> paths(v.mapKeys { it.key.toString() }, "$path.") + path
                    is List<*> ->
                        v.filterIsInstance<Map<*, *>>().flatMap {
                            paths(
                                it.mapKeys { e ->
                                    e.key.toString()
                                },
                                "$path[].",
                            )
                        } +
                            path
                    else -> listOf(path)
                }
            }.toSet()

    /** Whether the serialised [envelope] has a value at [path] (`positions[].positionId`: in every element). */
    fun present(
        envelope: InsightsEnvelope,
        path: String,
    ): Boolean {
        fun walk(
            e: JsonElement?,
            parts: List<String>,
        ): Boolean {
            if (parts.isEmpty()) return e != null
            val head = parts.first()
            return if (head.endsWith("[]")) {
                val list = (e as? JsonObject)?.get(head.removeSuffix("[]")) as? JsonArray ?: return false
                list.isNotEmpty() && list.all { walk(it, parts.drop(1)) }
            } else {
                walk((e as? JsonObject)?.get(head), parts.drop(1))
            }
        }
        val json = Json.parseToJsonElement(envelope.toJson("test")) as JsonObject
        return walk(json["payload"], path.split('.'))
    }
}

/** What the MT5 gateway answers in [ConnectorEnvelopeShapeTest]: an Exness hedging demo with one position. */
internal object Mt5Wire {
    const val MT5_ACCOUNT =
        """{"balance":7824.05,"equity":7676.54,"currency":"USD","leverage":100,"margin_mode":2,""" +
            """"margin":540.97,"margin_free":7135.57,"margin_level":1419.03,"profit":-147.51,""" +
            """"login":435898347,"server":"Exness-MT5Trial9","name":"qkt-hedge-straddle"}"""
    const val MT5_POSITIONS =
        """[{"ticket":123,"symbol":"XAUUSDm","type":0,"volume":"0.01",""" +
            """"price_open":"2300.5","price_current":"2310.2","sl":"0","tp":"0",""" +
            """"profit":"9.7","swap":"0","magic":10001,"time_msc":1700000000000,"comment":"dsl-hedge_straddle"}]"""
    const val MT5_ORDERS =
        """[{"ticket":501,"symbol":"XAUUSDm","type":"ORDER_TYPE_BUY_LIMIT","volume":"0.01",""" +
            """"price_open":"2250.0","sl":"2200.0","tp":"2400.0","magic":10001,""" +
            """"time_setup":1700000000000,"comment":"dsl-hedge_straddle"}]"""
    const val MT5_DEALS =
        """[{"ticket":456,"order":789,"position_id":123,"symbol":"XAUUSDm","type":0,"entry":0,""" +
            """"volume":"0.02","price":"2300.5","profit":"0","commission":"-0.07","swap":"0",""" +
            """"fee":"0","magic":10001,"comment":"dsl-hedge_straddle","reason":3,"time_msc":1700040000000},""" +
            """{"ticket":457,"order":790,"position_id":123,"symbol":"XAUUSDm","type":1,"entry":1,""" +
            """"volume":"0.01","price":"2310.2","profit":"9.7","commission":"-0.07","swap":"-0.12",""" +
            """"fee":"-0.75","magic":10001,"comment":"[tp 2310.2]","reason":5,"time_msc":1700050000000}]"""
}
