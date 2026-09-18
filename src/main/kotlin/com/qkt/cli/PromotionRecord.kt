package com.qkt.cli

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/** Paper-trading validation results recorded against a promotion. */
@Serializable
data class PaperValidationMetrics(
    val days: Int = 0,
    val trades: Int = 0,
    val avgSlippageBps: Double? = null,
    val p95SlippageBps: Double? = null,
    val rejectionRatePct: Double? = null,
    val missedFills: Int? = null,
    val status: String? = null,
)

/** An operator approval of a strategy for [state], with the actor and reason. */
@Serializable
data class PromotionApproval(
    val state: PromotionState,
    val actor: String,
    val reason: String,
    val approvedAt: String,
)

/**
 * One append-only promotion ledger entry for a strategy version (by hash): its state, evidence,
 * paper metrics, approvals and waivers. Each update is a new record with a fresh id.
 */
@Serializable
data class PromotionRecord(
    val id: String,
    val strategy: String,
    val strategyHash: String,
    val state: PromotionState,
    val createdAt: String,
    val updatedAt: String,
    val rationale: String,
    val evidence: Map<String, String> = emptyMap(),
    val paper: PaperValidationMetrics? = null,
    val approvals: List<PromotionApproval> = emptyList(),
    val waivers: List<PromotionWaiver> = emptyList(),
) {
    fun approvedFor(requiredState: PromotionState): Boolean =
        approvals.any { approval ->
            approval.state.atLeast(requiredState) && approval.reason.isNotBlank()
        }

    fun update(
        now: Instant,
        state: PromotionState = this.state,
        rationale: String = this.rationale,
        evidence: Map<String, String> = this.evidence,
        paper: PaperValidationMetrics? = this.paper,
        approvals: List<PromotionApproval> = this.approvals,
        waivers: List<PromotionWaiver> = this.waivers,
    ): PromotionRecord =
        copy(
            id = UUID.randomUUID().toString(),
            state = state,
            updatedAt = now.toString(),
            rationale = rationale,
            evidence = evidence,
            paper = paper,
            approvals = approvals,
            waivers = waivers,
        )

    companion object {
        fun create(
            strategy: String,
            strategyHash: String,
            state: PromotionState,
            rationale: String,
            now: Instant,
            evidence: Map<String, String> = emptyMap(),
            paper: PaperValidationMetrics? = null,
            approvals: List<PromotionApproval> = emptyList(),
            waivers: List<PromotionWaiver> = emptyList(),
        ): PromotionRecord =
            PromotionRecord(
                id = UUID.randomUUID().toString(),
                strategy = strategy,
                strategyHash = strategyHash,
                state = state,
                createdAt = now.toString(),
                updatedAt = now.toString(),
                rationale = rationale,
                evidence = evidence,
                paper = paper,
                approvals = approvals,
                waivers = waivers,
            )
    }
}
