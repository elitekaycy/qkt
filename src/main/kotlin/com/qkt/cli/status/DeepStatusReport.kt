package com.qkt.cli.status

import com.qkt.cli.BuildInfo
import com.qkt.cli.ExitCodes
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Renders the `qkt status --deep` health screen from the daemon's `/health` and `/list` bodies and
 * returns the exit code: success only when the daemon and every strategy are healthy.
 */
internal fun renderDeepStatus(
    healthBody: String,
    listBody: String,
): Int {
    val health: JsonObject
    val strategies: JsonArray
    try {
        health = Json.parseToJsonElement(healthBody).jsonObject
        strategies = Json.parseToJsonElement(listBody).jsonArray
    } catch (e: SerializationException) {
        println("qkt: UNHEALTHY")
        System.err.println("CONTROL      malformed daemon response: ${e.message}")
        return ExitCodes.USER_ERROR
    } catch (e: IllegalArgumentException) {
        println("qkt: UNHEALTHY")
        System.err.println("CONTROL      unexpected daemon response shape: ${e.message}")
        return ExitCodes.USER_ERROR
    }

    val daemonStatus = health["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
    val uptimeMs = health["uptimeMs"]?.jsonPrimitive?.longOrNull ?: 0L

    val unhealthy = mutableListOf<String>()
    val versionLine = BuildInfo.versionLine()

    val lines = mutableListOf<String>()
    lines.add(versionLine)
    lines.add("")
    if (daemonStatus == "ok") {
        lines.add("DAEMON       running (uptime ${formatUptime(uptimeMs)})")
    } else {
        lines.add("DAEMON       $daemonStatus (uptime ${formatUptime(uptimeMs)})")
        unhealthy.add("daemon status=$daemonStatus")
    }
    lines.add("CONTROL      reachable")
    lines.add(renderStrategies(strategies, unhealthy))

    if (unhealthy.isEmpty()) {
        println("qkt: HEALTHY")
        println("")
        for (l in lines) println(l)
        return ExitCodes.SUCCESS
    }
    println("qkt: UNHEALTHY (${unhealthy.size} issue${if (unhealthy.size == 1) "" else "s"})")
    println("")
    for (l in lines) println(l)
    System.err.println("")
    for (u in unhealthy) System.err.println("  - $u")
    return ExitCodes.USER_ERROR
}
