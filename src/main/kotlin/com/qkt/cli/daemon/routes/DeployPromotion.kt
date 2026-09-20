package com.qkt.cli.daemon.routes

import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.PromotionGateEvaluator
import com.qkt.cli.PromotionGateResult
import com.qkt.cli.PromotionRecord
import com.qkt.cli.PromotionState
import com.qkt.cli.PromotionWaiver
import com.qkt.cli.daemon.OperatorJournal
import com.qkt.cli.daemon.StateDir
import com.sun.net.httpserver.HttpExchange
import java.nio.file.Path
import java.time.Instant

/**
 * Records any `waive` query waiver, then evaluates the promotion gates for a
 * deploy or resync. Returns `null` after answering 400 for a waiver without a reason.
 */
internal fun evaluateDeployPromotion(
    ex: HttpExchange,
    name: String,
    path: Path,
    stateDir: StateDir?,
    gates: PromotionGateConfig,
    params: Map<String, String>,
): PromotionGateResult? {
    val store = promotionStore(stateDir, gates)
    val now = Instant.now()
    val waiver = deployWaiver(params, now)
    if (params.containsKey("waive") && waiver == null) {
        respond(ex, 400, """{"error":"--waive requires a non-empty reason"}""")
        return null
    }
    if (waiver != null) {
        val strategyHash = PromotionGateEvaluator.strategyHash(path)
        val existing = store.latest(name, strategyHash)
        val record =
            existing
                ?.update(now = now, waivers = existing.waivers + waiver)
                ?: PromotionRecord.create(
                    strategy = name,
                    strategyHash = strategyHash,
                    state = PromotionState.DRAFT,
                    rationale = "deploy waiver without prior promotion record",
                    now = now,
                    waivers = listOf(waiver),
                )
        store.append(record)
        OperatorJournal
            .from(stateDir, "http")
            ?.record(
                action = "promotion.waive",
                target = name,
                affected = listOf(name),
                details =
                    mapOf(
                        "gates" to waiver.gates.joinToString(","),
                        "reason" to waiver.reason,
                        "strategyHash" to strategyHash,
                        "expiresAt" to waiver.expiresAt,
                    ),
            )
    }
    return PromotionGateEvaluator(gates)
        .evaluate(
            strategy = name,
            strategyPath = path,
            store = store,
            now = now,
        )
}

private fun deployWaiver(
    params: Map<String, String>,
    now: Instant,
): PromotionWaiver? {
    val raw = params["waive"] ?: return null
    val reason = params["reason"]?.takeIf { it.isNotBlank() } ?: return null
    val gates =
        raw
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("all") }
    val expiresAt = params["expires"]?.takeIf { it.isNotBlank() }
    if (expiresAt != null && runCatching { Instant.parse(expiresAt) }.isFailure) return null
    return PromotionWaiver(
        gates = gates,
        reason = reason,
        actor = params["actor"]?.takeIf { it.isNotBlank() } ?: "deploy",
        createdAt = now.toString(),
        expiresAt = expiresAt,
    )
}
