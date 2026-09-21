package com.qkt.cli.daemon.routes

import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.PromotionGateEvaluator
import com.qkt.cli.PromotionStore
import com.qkt.cli.daemon.HaltStatus
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyRegistry
import com.sun.net.httpserver.HttpExchange
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive

/**
 * `GET /list` — one row per portfolio, portfolio child and standalone strategy, with
 * promotion-gate fields when gates are enforced or a promotion record exists.
 */
internal fun handleList(
    ex: HttpExchange,
    registry: StrategyRegistry,
    stateDir: StateDir?,
    promotionGates: PromotionGateConfig,
) {
    val now = Instant.now().toEpochMilli()
    val rows = mutableListOf<String>()
    val promotionStore = promotionStore(stateDir, promotionGates)
    for (record in registry.listPortfolios()) {
        val uptime = now - record.startedAt.toEpochMilli()
        val state = if (record.supervisor.running) "running" else "stopped"
        val aliases = record.children.mapNotNull { it.childMeta?.alias }
        val aliasJson = aliases.joinToString(",", "[", "]") { "\"$it\"" }
        val promotionJson =
            renderPromotionFields(
                name = record.name,
                sourceFile = null,
                store = promotionStore,
                gates = promotionGates,
            )
        rows.add(
            """{"name":"${record.name}","kind":"portfolio","childAliases":$aliasJson,""" +
                """"uptimeMs":$uptime,"state":"$state"$promotionJson}""",
        )
    }
    for (h in registry.list()) {
        val uptime = now - h.startedAt.toEpochMilli()
        val state = if (h.isRunning()) "running" else "stopped"
        val streamBrokersJson = renderStreamBrokers(h.live.streamBrokers())
        val meta = h.childMeta
        val haltJson = renderHaltFields(HaltStatus.of(h.live, h.name))
        val promotionJson =
            renderPromotionFields(
                // A portfolio is deployed and promoted as one unit. Its children inherit the
                // parent's gate result instead of requiring synthetic per-alias approvals.
                name = meta?.parent ?: h.name,
                sourceFile = h.sourceFile?.takeIf { meta == null },
                store = promotionStore,
                gates = promotionGates,
            )
        if (meta != null) {
            val gateState =
                when {
                    meta.operatorStop.get() -> "operator_stopped"
                    meta.gateActive.get() -> "active"
                    else -> "idle"
                }
            rows.add(
                """{"name":"${h.name}","kind":"child","parent":"${meta.parent}",""" +
                    """"port":${h.port},"trades":${h.tradeCount},""" +
                    """"uptimeMs":$uptime,"state":"$state","gateState":"$gateState",""" +
                    """"streamBrokers":$streamBrokersJson$haltJson$promotionJson}""",
            )
        } else {
            rows.add(
                """{"name":"${h.name}","kind":"strategy","port":${h.port},""" +
                    """"trades":${h.tradeCount},"uptimeMs":$uptime,"state":"$state",""" +
                    """"streamBrokers":$streamBrokersJson$haltJson$promotionJson}""",
            )
        }
    }
    respond(ex, 200, rows.joinToString(",", "[", "]"))
}

/** `,"halted":true,"haltReason":"…","haltScope":"…"` for a halted strategy, `,"halted":false` otherwise. */
private fun renderHaltFields(halt: HaltStatus): String {
    if (!halt.halted) return ""","halted":false"""
    val reason = JsonPrimitive(halt.reason ?: "").toString()
    val scope = JsonPrimitive(halt.scope ?: "").toString()
    return ""","halted":true,"haltReason":$reason,"haltScope":$scope"""
}

private fun renderPromotionFields(
    name: String,
    sourceFile: Path?,
    store: PromotionStore,
    gates: PromotionGateConfig,
): String {
    val result =
        PromotionGateEvaluator(gates)
            .evaluate(
                strategy = name,
                strategyPath = sourceFile?.takeIf { Files.exists(it) },
                store = store,
            )
    if (!gates.enforce && result.recordId == null) return ""
    return ""","promotionEnforced":${result.enforced},""" +
        """"promotionState":${jsonStringOrNull(result.state)},""" +
        """"promotionEligible":${result.eligibleForProduction},""" +
        """"promotionMissingGates":${jsonArray(result.missingGates)},""" +
        """"strategyHash":${jsonStringOrNull(result.strategyHash)}"""
}

private fun renderStreamBrokers(map: Map<String, String>): String {
    if (map.isEmpty()) return "{}"
    return map.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
}
