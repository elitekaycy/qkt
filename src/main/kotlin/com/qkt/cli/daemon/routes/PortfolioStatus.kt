package com.qkt.cli.daemon.routes

import com.qkt.cli.daemon.PortfolioRecord
import com.qkt.cli.daemon.StrategyRegistry
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Aggregates a portfolio's children `/status` bodies into one JSON object: summed
 * equity, balance, realized and unrealized PnL, plus one row per reachable child.
 */
internal fun composePortfolioStatus(
    registry: StrategyRegistry,
    record: PortfolioRecord,
): String {
    val now = System.currentTimeMillis()
    val children = registry.childrenOf(record.name)
    var realized = java.math.BigDecimal.ZERO
    var unrealized = java.math.BigDecimal.ZERO
    var equity = java.math.BigDecimal.ZERO
    var balance = java.math.BigDecimal.ZERO
    val childRows = mutableListOf<kotlinx.serialization.json.JsonObject>()
    for (c in children) {
        val meta = c.childMeta ?: continue
        val raw = fetchStrategyStatus(c.port) ?: continue
        val obj = runCatching { routeJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
        realized =
            realized +
            (obj["realized"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
        unrealized =
            unrealized +
            (obj["unrealized"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
        equity =
            equity +
            (obj["equity"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
        balance =
            balance +
            (obj["balance"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
        childRows.add(
            kotlinx.serialization.json.buildJsonObject {
                put("alias", kotlinx.serialization.json.JsonPrimitive(meta.alias))
                put("name", kotlinx.serialization.json.JsonPrimitive(c.name))
                put("port", kotlinx.serialization.json.JsonPrimitive(c.port))
                put("gateActive", kotlinx.serialization.json.JsonPrimitive(meta.gateActive.get()))
                put("operatorStop", kotlinx.serialization.json.JsonPrimitive(meta.operatorStop.get()))
                put("hold", kotlinx.serialization.json.JsonPrimitive(meta.hold))
                put("trades", kotlinx.serialization.json.JsonPrimitive(c.tradeCount))
                put("realized", obj["realized"] ?: kotlinx.serialization.json.JsonPrimitive("0"))
                put("unrealized", obj["unrealized"] ?: kotlinx.serialization.json.JsonPrimitive("0"))
            },
        )
    }
    return kotlinx.serialization.json
        .buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive(record.name))
            put("kind", kotlinx.serialization.json.JsonPrimitive("portfolio"))
            put("version", kotlinx.serialization.json.JsonPrimitive(record.version))
            put("startedAt", kotlinx.serialization.json.JsonPrimitive(record.startedAt.toString()))
            put("uptimeMs", kotlinx.serialization.json.JsonPrimitive(now - record.startedAt.toEpochMilli()))
            put("supervisorRunning", kotlinx.serialization.json.JsonPrimitive(record.supervisor.running))
            put("equity", kotlinx.serialization.json.JsonPrimitive(equity.toPlainString()))
            put("balance", kotlinx.serialization.json.JsonPrimitive(balance.toPlainString()))
            put("realized", kotlinx.serialization.json.JsonPrimitive(realized.toPlainString()))
            put("unrealized", kotlinx.serialization.json.JsonPrimitive(unrealized.toPlainString()))
            put("children", kotlinx.serialization.json.JsonArray(childRows))
        }.toString()
}
