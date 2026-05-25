package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * `qkt status [name]` — daemon-wide or per-strategy JSON status snapshot.
 *
 * `qkt status --deep` returns a single-screen human-readable health summary
 * aggregated across daemon + control plane + every deployed strategy. Exit code
 * is `0` when every subsystem is healthy, `1` if anything is wrong — designed
 * as the first-thing-to-run when an operator suspects something is off.
 */
class StatusCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        if (args.flag("deep")) return runDeep()
        return runShallow()
    }

    private fun runShallow(): Int {
        val name = args.positional(0)
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body =
            try {
                client.status(name)
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                if (e.code == 404) {
                    System.err.println("qkt: error: unknown strategy: $name")
                } else {
                    System.err.println("qkt: error: status failed (${e.code}): ${e.body}")
                }
                return ExitCodes.USER_ERROR
            }
        println(body)
        return ExitCodes.SUCCESS
    }

    private fun runDeep(): Int {
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val healthBody =
            try {
                client.health()
            } catch (e: ControlClient.NoDaemonRunningException) {
                println("qkt: UNHEALTHY")
                System.err.println("DAEMON       not running (${e.message})")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                println("qkt: UNHEALTHY")
                System.err.println("DAEMON       /health failed (${e.code}): ${e.body}")
                return ExitCodes.USER_ERROR
            }
        val statusBody =
            try {
                client.status(null)
            } catch (e: ControlClient.DaemonError) {
                println("qkt: UNHEALTHY")
                System.err.println("CONTROL      /status failed (${e.code}): ${e.body}")
                return ExitCodes.USER_ERROR
            }
        return renderDeep(healthBody, statusBody)
    }

    private fun renderDeep(
        healthBody: String,
        statusBody: String,
    ): Int {
        val health = Json.parseToJsonElement(healthBody).jsonObject
        val strategies = Json.parseToJsonElement(statusBody).jsonArray

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

    private fun renderStrategies(
        strategies: JsonArray,
        unhealthy: MutableList<String>,
    ): String {
        if (strategies.isEmpty()) return "STRATEGIES   none deployed"
        val sb = StringBuilder()
        sb.append("STRATEGIES")
        for (entry in strategies) {
            val obj = entry.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "?"
            val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "?"
            val trades = obj["trades"]?.jsonPrimitive?.intOrNull ?: 0
            val uptimeMs = obj["uptimeMs"]?.jsonPrimitive?.longOrNull ?: 0L
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "strategy"
            val gateState = obj["gateState"]?.jsonPrimitive?.contentOrNull
            val tag =
                buildString {
                    if (kind == "child") append("[child]")
                    if (gateState == "operator_stopped") append("[OP_STOPPED]")
                }
            sb.append('\n')
            sb.append(
                "  %-20s %s, %d trades, up %s %s".format(name, state, trades, formatUptime(uptimeMs), tag).trimEnd(),
            )
            if (state != "running") {
                unhealthy.add("strategy '$name' state=$state")
            }
            if (gateState == "operator_stopped") {
                unhealthy.add("strategy '$name' is operator-stopped")
            }
        }
        return sb.toString()
    }

    private fun formatUptime(ms: Long): String {
        val seconds = ms / 1_000
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        val s = seconds % 60
        return when {
            days > 0 -> "${days}d${hours}h"
            hours > 0 -> "${hours}h${minutes}m"
            minutes > 0 -> "${minutes}m${s}s"
            else -> "${s}s"
        }
    }
}
