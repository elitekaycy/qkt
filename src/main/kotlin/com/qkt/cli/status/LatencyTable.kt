package com.qkt.cli.status

import com.qkt.cli.ExitCodes
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Renders the daemon's `/latency` body as a per-strategy, per-stage percentile table and returns
 * the exit code; a malformed body is a user error.
 */
internal fun renderLatencyTable(body: String): Int {
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
