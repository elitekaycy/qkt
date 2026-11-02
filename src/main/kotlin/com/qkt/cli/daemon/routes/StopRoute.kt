package com.qkt.cli.daemon.routes

import com.qkt.app.FlattenResult
import com.qkt.cli.daemon.OperatorJournal
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyRegistry
import com.sun.net.httpserver.HttpExchange

/**
 * `POST /stop/{name}` — stops a portfolio (flattening non-hold children by default),
 * operator-stops a portfolio child, or stops a standalone strategy. A verified flatten that did not end flat
 * answers `not_flat` ([stopAnswer]), not the state a flat stop would.
 */
internal fun handleStop(
    ex: HttpExchange,
    registry: StrategyRegistry,
    stateDir: StateDir?,
    path: String,
) {
    val name = path.removePrefix("/stop/").trim('/').ifBlank { null }
    if (name == null) {
        return respond(ex, 400, """{"error":"missing name in path"}""")
    }
    val params = parseQuery(ex.requestURI.rawQuery)
    if (params.containsKey("timeout")) {
        val t = params["timeout"]?.toLongOrNull()
        if (t == null || t < 0) {
            return respond(ex, 400, """{"error":"invalid 'timeout' query param"}""")
        }
    }
    val flattenOverride: Boolean? =
        when (val raw = params["flatten"]) {
            null -> null
            "true" -> true
            "false" -> false
            else -> return respond(ex, 400, """{"error":"invalid 'flatten' query param"}""")
        }

    registry.getPortfolio(name)?.let { record ->
        record.supervisor.stop()
        var totalTrades = 0
        val flats = mutableListOf<Pair<String, FlattenResult>>()
        for (child in record.children) {
            val meta = child.childMeta
            val shouldFlatten = flattenOverride ?: (meta != null && !meta.hold)
            if (shouldFlatten) {
                flats += child.name to child.live.verifiedStopFlatten()
            }
            totalTrades += child.tradeCount
        }
        registry.removePortfolio(name)
        for (child in record.children) runCatching { child.close() }
        OperatorJournal
            .from(stateDir, "http")
            ?.record(
                action = "stop",
                target = name,
                affected = listOf(name) + record.children.map { it.name },
                details = mapOf("flatten" to (flattenOverride?.toString() ?: "default")),
            )
        return respond(ex, 200, stopAnswer(name, "stopped", totalTrades, flats))
    }

    val handle =
        registry.get(name)
            ?: return respond(ex, 404, """{"error":${jsonString("unknown name: $name")}}""")
    val meta = handle.childMeta
    if (meta != null) {
        meta.operatorStop.set(true)
        meta.gateActive.set(false)
        val shouldFlatten = flattenOverride ?: !meta.hold
        // Only an operator's explicit --flatten waits to be confirmed; a child's default flatten stays as it was.
        val flat = if (flattenOverride == true) handle.live.verifiedStopFlatten() else null
        if (shouldFlatten && flat == null) runCatching { handle.live.flatten() }
        OperatorJournal
            .from(stateDir, "http")
            ?.record(
                action = "stop",
                target = name,
                affected = listOf(name),
                details = mapOf("flatten" to shouldFlatten.toString(), "state" to "operator_stopped"),
            )
        val flats = listOfNotNull(flat?.let { name to it })
        return respond(ex, 200, stopAnswer(name, "operator_stopped", handle.tradeCount, flats))
    }
    val trades = handle.tradeCount
    val flat = if (flattenOverride == true) handle.live.verifiedStopFlatten() else null
    registry.stop(name)
    OperatorJournal
        .from(stateDir, "http")
        ?.record(
            action = "stop",
            target = name,
            affected = listOf(name),
            details = mapOf("flatten" to (flattenOverride?.toString() ?: "false")),
        )
    respond(ex, 200, stopAnswer(name, "stopped", trades, listOfNotNull(flat?.let { name to it })))
}
