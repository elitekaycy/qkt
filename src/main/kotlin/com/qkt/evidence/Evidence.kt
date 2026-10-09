package com.qkt.evidence

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class EvidenceEnvelope(
    val qktVersion: String,
    val gitSha: String,
    val buildTimestamp: String,
    val dslPercentConvention: String = "whole-percentage-points",
    val command: List<String> = emptyList(),
    val strategyHash: String,
    val importedFileHashes: Map<String, String> = emptyMap(),
    val configHash: String? = null,
    val dataset: DatasetEvidence? = null,
    val execution: ExecutionEvidence? = null,
    val experiment: ExperimentEvidence? = null,
    val accounting: AccountingEvidence? = null,
    val promotion: PromotionEvidence? = null,
    val warnings: List<String> = emptyList(),
    /**
     * Effective configuration per key: merged value plus which layer supplied it
     * (`flag`, `run:<name>`, `global`). Empty when the run used CLI flags alone.
     */
    val resolved: Map<String, ResolvedValue> = emptyMap(),
)

/** One merged configuration value and the layer that supplied it (see [EvidenceEnvelope.resolved]). */
data class ResolvedValue(
    val value: String,
    val from: String,
)

data class DatasetEvidence(
    val id: String? = null,
    val hash: String? = null,
    val qualityPolicy: String? = null,
    val mutableStore: Boolean = false,
    val warning: String? = null,
)

data class ExecutionEvidence(
    val preset: String,
    val broker: String,
    val seed: Long? = null,
    val realistic: Boolean = false,
    val fillPriceSource: String? = null,
    val latencyModel: String? = null,
    /** Delay between a protective stop's trigger and its fill: `on-trigger` or `fixed:<n>ms`. */
    val stopLatencyModel: String? = null,
    /** Pricing of a gap-crossed take-profit: `print` (crossing print or better) or `level`. */
    val takeProfitFillModel: String? = null,
    /** How replay closes a quiet symbol's ended bar: `heartbeat:<n>ms grace:<n>ms`, matching live (#1138). */
    val candleCloseModel: String? = null,
    val slippageModel: String? = null,
    val rejectionModel: String? = null,
    val partialFillModel: String? = null,
    val venueRules: String? = null,
    val commissionModel: String? = null,
    /** Human-readable overnight financing assumptions used by the run. */
    val financingModel: String? = null,
    val ocoMode: String? = null,
    val warning: String? = null,
)

data class ExperimentEvidence(
    val id: String? = null,
    val trialCount: Int? = null,
    val primaryMetric: String? = null,
    val splits: Map<String, String> = emptyMap(),
    val selectedLabel: String? = null,
    val selectedParams: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val warning: String? = null,
)

data class AccountingEvidence(
    val accountCurrency: String? = null,
    val missingPolicy: String? = null,
    val source: String? = null,
    val configuredFxSymbols: Map<String, String> = emptyMap(),
    val conversions: Map<String, String> = emptyMap(),
    val costKinds: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val warning: String? = null,
)

data class PromotionEvidence(
    val state: String? = null,
    val eligibleForProduction: Boolean? = null,
    val missingGates: List<String> = emptyList(),
    val rationale: String? = null,
    val warning: String? = null,
)

object EvidenceHasher {
    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        return "sha256:${hex(digest)}"
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
}
