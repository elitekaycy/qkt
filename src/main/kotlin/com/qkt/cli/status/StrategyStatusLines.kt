package com.qkt.cli.status

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The STRATEGIES section of the deep status screen: one line per deployment with its stream routing
 * and promotion state. Appends a reason to [unhealthy] for each strategy that is not healthy, and
 * to [halted] for each one that risk control has stopped from entering.
 */
internal fun renderStrategies(
    strategies: JsonArray,
    unhealthy: MutableList<String>,
    halted: MutableList<String> = mutableListOf(),
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
        val promotionEnforced = obj["promotionEnforced"]?.jsonPrimitive?.booleanOrNull ?: false
        val promotionState = obj["promotionState"]?.jsonPrimitive?.contentOrNull
        val promotionEligible = obj["promotionEligible"]?.jsonPrimitive?.booleanOrNull
        val promotionMissing =
            obj["promotionMissingGates"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
        val isHalted = obj["halted"]?.jsonPrimitive?.booleanOrNull ?: false
        val haltReason = obj["haltReason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val tag =
            buildString {
                if (kind == "child") append("[child]")
                if (isHalted) append("[HALTED]")
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
        if (promotionEnforced || promotionState != null || promotionMissing.isNotEmpty()) {
            sb.append("\n    promotion: ")
            sb.append(promotionState ?: "none")
            sb.append(" eligible=")
            sb.append(
                when (promotionEligible) {
                    true -> "yes"
                    false -> "no"
                    null -> "unknown"
                },
            )
            if (promotionMissing.isNotEmpty()) {
                sb.append(" missing=").append(promotionMissing.joinToString(","))
            }
        }
        if (state != "running") {
            unhealthy.add("strategy '$name' state=$state")
        }
        if (isHalted) {
            sb.append("\n    halted: ").append(haltReason.ifEmpty { "no reason recorded" })
            // A halt is risk control doing its job, not a fault: it is shown, and named at the top of the
            // screen, but it does not fail the health check. Container health and deploy verification
            // run this command - a drawdown halt must not mark a working daemon unhealthy.
            halted.add("strategy '$name' is halted ($haltReason) - clear with: qkt resume $name")
        }
        if (gateState == "operator_stopped") {
            unhealthy.add("strategy '$name' is operator-stopped")
        }
        if (promotionEnforced && promotionEligible == false) {
            unhealthy.add("strategy '$name' promotion gates missing: ${promotionMissing.joinToString(",")}")
        }
    }
    return sb.toString()
}

/** Compact uptime such as `2d3h`, `4h10m`, `5m2s` or `9s`. */
internal fun formatUptime(ms: Long): String {
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
