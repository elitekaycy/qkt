package com.qkt.cli

import kotlinx.serialization.Serializable

/** The outcome of evaluating a strategy against the promotion gates; [blocked] when enforced and ineligible. */
@Serializable
data class PromotionGateResult(
    val strategy: String,
    val state: String? = null,
    val recordId: String? = null,
    val strategyHash: String? = null,
    val paper: PaperValidationMetrics? = null,
    val requiredState: String,
    val enforced: Boolean,
    val eligibleForProduction: Boolean,
    val missingGates: List<String> = emptyList(),
    val waivedGates: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val blocked: Boolean
        get() = enforced && !eligibleForProduction
}
