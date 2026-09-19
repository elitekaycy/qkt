package com.qkt.cli.daemon.routes

import com.qkt.cli.daemon.StrategyHandle
import com.qkt.cli.daemon.StrategyRegistry
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.jsonObject

/**
 * `GET /status/{name}` — a portfolio's aggregate status, or a strategy's own `/status`
 * body (children annotated with their portfolio gate fields).
 */
internal fun handleStatusOne(
    ex: HttpExchange,
    registry: StrategyRegistry,
    path: String,
) {
    val name = path.removePrefix("/status/").trim('/').ifBlank { null }
    if (name == null) return respond(ex, 400, """{"error":"missing name in path"}""")
    registry.getPortfolio(name)?.let { record ->
        return respond(ex, 200, composePortfolioStatus(registry, record))
    }
    val handle =
        registry.get(name)
            ?: return respond(ex, 404, """{"error":"unknown name: $name"}""")
    val body =
        fetchStrategyStatus(handle.port)
            ?: return respond(ex, 502, """{"error":"strategy /status unreachable"}""")
    if (handle.childMeta != null) {
        respond(ex, 200, augmentChildStatus(body, handle))
    } else {
        respond(ex, 200, body)
    }
}

private fun augmentChildStatus(
    body: String,
    handle: StrategyHandle,
): String {
    val obj = routeJson.parseToJsonElement(body).jsonObject
    val meta = handle.childMeta ?: return body
    val updated =
        kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in obj) put(k, v)
            put("kind", kotlinx.serialization.json.JsonPrimitive("child"))
            put("parent", kotlinx.serialization.json.JsonPrimitive(meta.parent))
            put("alias", kotlinx.serialization.json.JsonPrimitive(meta.alias))
            put("gateActive", kotlinx.serialization.json.JsonPrimitive(meta.gateActive.get()))
            put("operatorStop", kotlinx.serialization.json.JsonPrimitive(meta.operatorStop.get()))
            put("hold", kotlinx.serialization.json.JsonPrimitive(meta.hold))
        }
    return updated.toString()
}

/**
 * `GET /status` — every portfolio's aggregate status followed by each standalone
 * strategy's `/status` body; unreachable strategies are omitted.
 */
internal fun handleStatusAll(
    ex: HttpExchange,
    registry: StrategyRegistry,
) {
    val portfolioParts = registry.listPortfolios().map { composePortfolioStatus(registry, it) }
    val strategyParts =
        registry.list().filter { it.childMeta == null }.mapNotNull { h ->
            fetchStrategyStatus(h.port)
        }
    val all = portfolioParts + strategyParts
    respond(ex, 200, all.joinToString(separator = ",", prefix = "[", postfix = "]"))
}
