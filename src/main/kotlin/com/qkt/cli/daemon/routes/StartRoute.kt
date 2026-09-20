package com.qkt.cli.daemon.routes

import com.qkt.cli.daemon.OperatorJournal
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyRegistry
import com.sun.net.httpserver.HttpExchange

/**
 * `POST /start/{name}` — clears an operator stop on a portfolio child. Standalone
 * strategies and portfolios are started by deploy instead.
 */
internal fun handleStart(
    ex: HttpExchange,
    registry: StrategyRegistry,
    stateDir: StateDir?,
    path: String,
) {
    val name =
        path.removePrefix("/start/").trim('/').ifBlank { null }
            ?: return respond(ex, 400, """{"error":"missing name"}""")
    val handle = registry.get(name)
    if (handle != null && handle.childMeta != null) {
        handle.childMeta.operatorStop.set(false)
        OperatorJournal
            .from(stateDir, "http")
            ?.record("start", target = name, affected = listOf(name), details = mapOf("state" to "resumed"))
        return respond(ex, 200, """{"name":"$name","state":"resumed"}""")
    }
    if (handle != null) {
        return respond(ex, 400, """{"error":"strategy '$name' has no paused state"}""")
    }
    if (registry.getPortfolio(name) != null) {
        return respond(ex, 400, """{"error":"portfolio '$name' cannot be started; use deploy"}""")
    }
    respond(ex, 404, """{"error":"unknown name: $name"}""")
}
