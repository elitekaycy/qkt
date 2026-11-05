package com.qkt.cli.daemon.routes

import com.qkt.broker.StreamStopRelease
import com.qkt.cli.daemon.OperatorJournal
import com.qkt.cli.daemon.RegistryDaemonControl
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyRegistry
import com.qkt.cli.daemon.Target
import com.qkt.cli.daemon.summary
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
        return respond(ex, 404, """{"error":${jsonString("unknown name: ${result.unknown.first()}")}}""")
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
        return respond(ex, 404, """{"error":${jsonString("unknown name: ${result.unknown.first()}")}}""")
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

private fun stopJson(s: StreamStopRelease): String =
    """{"strategy":${jsonString(s.strategyId)},"stream":${jsonString(s.stream)},"stop":${jsonString(s.stop)},""" +
        """"cleared":${s.cleared},"stands":${jsonStringOrNull(s.stands)},"summary":${jsonString(s.summary())}}"""

/**
 * `POST /resume[/{name}]` — resumes one halted strategy, portfolio book or child, or every one, and clears the stream
 * stops that are safe to clear. `state` is `resumed` only when no stop stands; otherwise `stream_stopped`, and
 * `streamStops` says why. `bookHalts` names each portfolio book halt the resume cleared, with its reason. A child
 * whose book is halted is refused with 409.
 */
internal fun handleResume(
    ex: HttpExchange,
    registry: StrategyRegistry,
    stateDir: StateDir?,
    name: String?,
) {
    val target = if (name == null) Target.All else Target.Strategy(name)
    val result = RegistryDaemonControl(registry, OperatorJournal.from(stateDir, "http")).resume(target)
    if (result.unknown.isNotEmpty()) {
        return respond(ex, 404, """{"error":${jsonString("unknown name: ${result.unknown.first()}")}}""")
    }
    result.refused.values
        .firstOrNull()
        ?.let { return respond(ex, 409, """{"error":${jsonString(it)}}""") }
    val stops = result.streamStops
    val state = if (stops.all { it.cleared }) "resumed" else "stream_stopped"
    val stopList = stops.joinToString(",", transform = ::stopJson)
    val stopsJson = if (stops.isEmpty()) "" else ",\"streamStops\":[$stopList]"
    val books = result.bookHalts.entries.joinToString(",") { (book, why) -> "${jsonString(book)}:${jsonString(why)}" }
    val booksJson = if (result.bookHalts.isEmpty()) "" else ",\"bookHalts\":{$books}"
    respond(ex, 200, """{"state":"$state","affected":${jsonArray(result.affected)}$stopsJson$booksJson}""")
}
