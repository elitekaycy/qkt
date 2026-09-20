package com.qkt.cli.daemon.routes

import com.qkt.cli.daemon.OperatorJournal
import com.qkt.cli.daemon.RegistryDaemonControl
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyRegistry
import com.qkt.cli.daemon.Target
import com.sun.net.httpserver.HttpExchange

/** `POST /halt[/{name}]` — halts one strategy, or every strategy, through [RegistryDaemonControl]. */
internal fun handleHalt(
    ex: HttpExchange,
    registry: StrategyRegistry,
    stateDir: StateDir?,
    name: String?,
) {
    val target = if (name == null) Target.All else Target.Strategy(name)
    val result = RegistryDaemonControl(registry, OperatorJournal.from(stateDir, "http")).halt(target)
    if (result.unknown.isNotEmpty()) {
        return respond(ex, 404, """{"error":"unknown name: ${result.unknown.first()}"}""")
    }
    respond(ex, 200, """{"state":"halted","affected":${jsonArray(result.affected)}}""")
}

/**
 * `POST /kill[/{name}]` — kills one strategy or all of them, optionally flattening
 * (`flatten=true`) and reporting whether the account was verified flat.
 */
internal fun handleKill(
    ex: HttpExchange,
    registry: StrategyRegistry,
    stateDir: StateDir?,
    name: String?,
) {
    val params = parseQuery(ex.requestURI.rawQuery)
    val flatten =
        when (val raw = params["flatten"]) {
            null -> false
            "true" -> true
            "false" -> false
            else -> return respond(ex, 400, """{"error":"invalid 'flatten' query param"}""")
        }
    val target = if (name == null) Target.All else Target.Strategy(name)
    val result = RegistryDaemonControl(registry, OperatorJournal.from(stateDir, "http")).kill(target, flatten)
    if (result.unknown.isNotEmpty()) {
        return respond(ex, 404, """{"error":"unknown name: ${result.unknown.first()}"}""")
    }
    val flattenVerified =
        flatten && result.affected.isNotEmpty() && result.flattenResults.values.all { it.verifiedFlat }
    val remainingTickets =
        result.flattenResults.values
            .flatMap { it.remainingTickets }
            .distinct()
    val flattenDetails = result.flattenResults.mapValues { it.value.detail }
    val detailsJson =
        flattenDetails.entries.joinToString(prefix = "{", postfix = "}") { (strategy, detail) ->
            "${jsonString(strategy)}:${jsonStringOrNull(detail)}"
        }
    respond(
        ex,
        200,
        """{"state":"killed","flatten":$flatten,"flattenVerified":$flattenVerified,""" +
            """"remainingTickets":${jsonArray(remainingTickets)},"flattenDetails":$detailsJson,""" +
            """"affected":${jsonArray(result.affected)}}""",
    )
}

/** `POST /resume[/{name}]` — resumes one halted strategy, or every one. */
internal fun handleResume(
    ex: HttpExchange,
    registry: StrategyRegistry,
    stateDir: StateDir?,
    name: String?,
) {
    val target = if (name == null) Target.All else Target.Strategy(name)
    val result = RegistryDaemonControl(registry, OperatorJournal.from(stateDir, "http")).resume(target)
    if (result.unknown.isNotEmpty()) {
        return respond(ex, 404, """{"error":"unknown name: ${result.unknown.first()}"}""")
    }
    respond(ex, 200, """{"state":"resumed","affected":${jsonArray(result.affected)}}""")
}
