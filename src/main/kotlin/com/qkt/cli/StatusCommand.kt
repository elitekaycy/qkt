package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
        if (args.flag("latency")) return runLatency()
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
        val listBody =
            try {
                client.list()
            } catch (e: ControlClient.NoDaemonRunningException) {
                println("qkt: UNHEALTHY")
                System.err.println("DAEMON       not running (${e.message})")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                println("qkt: UNHEALTHY")
                System.err.println("CONTROL      /list failed (${e.code}): ${e.body}")
                return ExitCodes.USER_ERROR
            }
        return renderDeep(healthBody, listBody)
    }

    private fun renderDeep(
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
            // Per-stream broker routing (#139). Only present when the strategy is DSL-compiled
            // and the daemon's StatusSnapshot includes the map; empty otherwise.
            val streamBrokers = obj["streamBrokers"]?.jsonObject
            if (streamBrokers != null && streamBrokers.isNotEmpty()) {
                val pairs =
                    streamBrokers.entries.joinToString(", ") { (alias, broker) ->
                        "$alias→${broker.jsonPrimitive.contentOrNull ?: "?"}"
                    }
                sb.append("\n    streams: ").append(pairs)
            }
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

    /**
     * `qkt status --latency` — per-strategy per-stage latency percentiles. Hits the
     * daemon's `/latency` aggregator and renders a table. See #150.
     */
    private fun runLatency(): Int {
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body =
            try {
                client.latency()
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                System.err.println("qkt: error: latency fetch failed (${e.code}): ${e.body}")
                return ExitCodes.USER_ERROR
            }
        val root: JsonObject =
            try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: SerializationException) {
                System.err.println("qkt: error: malformed /latency response: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        if (root.isEmpty()) {
            println("(no strategies deployed)")
            return ExitCodes.SUCCESS
        }
        println("STRATEGY              STAGE                  COUNT     p50      p95      p99      MAX")
        for ((stratName, perStrat) in root) {
            val perStratObj = perStrat.jsonObject
            val enabled = perStratObj["enabled"]?.jsonPrimitive?.contentOrNull
            if (enabled != "true") {
                println("%-20s  (disabled — set QKT_LATENCY_TRACKING=1)".format(stratName))
                continue
            }
            val strategies = perStratObj["strategies"]?.jsonObject ?: continue
            for ((innerId, byStage) in strategies) {
                val displayName = if (strategies.size == 1) stratName else "$stratName/$innerId"
                for ((stage, snap) in byStage.jsonObject) {
                    val s = snap.jsonObject
                    val count = s["count"]?.jsonPrimitive?.intOrNull ?: 0
                    val p50 = s["p50Nanos"]?.jsonPrimitive?.longOrNull ?: 0L
                    val p95 = s["p95Nanos"]?.jsonPrimitive?.longOrNull ?: 0L
                    val p99 = s["p99Nanos"]?.jsonPrimitive?.longOrNull ?: 0L
                    val max = s["maxNanos"]?.jsonPrimitive?.longOrNull ?: 0L
                    println(
                        "%-20s  %-22s %7d  %7s  %7s  %7s  %7s".format(
                            displayName,
                            stage,
                            count,
                            formatNanos(p50),
                            formatNanos(p95),
                            formatNanos(p99),
                            formatNanos(max),
                        ),
                    )
                }
            }
        }
        return ExitCodes.SUCCESS
    }

    private fun formatNanos(n: Long): String =
        when {
            n >= 1_000_000_000 -> "%.2fs".format(n / 1_000_000_000.0)
            n >= 1_000_000 -> "%.2fms".format(n / 1_000_000.0)
            n >= 1_000 -> "%.1fµs".format(n / 1_000.0)
            else -> "${n}ns"
        }
}
