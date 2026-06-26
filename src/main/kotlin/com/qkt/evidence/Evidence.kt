package com.qkt.evidence

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class EvidenceEnvelope(
    val qktVersion: String,
    val gitSha: String,
    val buildTimestamp: String,
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
    val slippageModel: String? = null,
    val rejectionModel: String? = null,
    val partialFillModel: String? = null,
    val venueRules: String? = null,
    val commissionModel: String? = null,
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

object EvidenceJson {
    fun render(e: EvidenceEnvelope): String =
        buildString {
            append("{")
            append("\"qktVersion\":").append(jsonString(e.qktVersion))
            append(",\"gitSha\":").append(jsonString(e.gitSha))
            append(",\"buildTimestamp\":").append(jsonString(e.buildTimestamp))
            append(",\"command\":").append(stringList(e.command))
            append(",\"strategyHash\":").append(jsonString(e.strategyHash))
            append(",\"importedFileHashes\":").append(stringMap(e.importedFileHashes))
            append(",\"configHash\":").append(nullableString(e.configHash))
            append(",\"dataset\":").append(e.dataset?.let(::dataset) ?: "null")
            append(",\"execution\":").append(e.execution?.let(::execution) ?: "null")
            append(",\"experiment\":").append(e.experiment?.let(::experiment) ?: "null")
            append(",\"accounting\":").append(e.accounting?.let(::accounting) ?: "null")
            append(",\"promotion\":").append(e.promotion?.let(::promotion) ?: "null")
            append(",\"warnings\":").append(stringList(e.warnings))
            append("}")
        }

    private fun dataset(d: DatasetEvidence): String =
        buildString {
            append("{\"id\":").append(nullableString(d.id))
            append(",\"hash\":").append(nullableString(d.hash))
            append(",\"qualityPolicy\":").append(nullableString(d.qualityPolicy))
            append(",\"mutableStore\":").append(d.mutableStore)
            append(",\"warning\":").append(nullableString(d.warning))
            append("}")
        }

    private fun execution(e: ExecutionEvidence): String =
        buildString {
            append("{\"preset\":").append(jsonString(e.preset))
            append(",\"broker\":").append(jsonString(e.broker))
            append(",\"seed\":").append(e.seed?.toString() ?: "null")
            append(",\"realistic\":").append(e.realistic)
            append(",\"fillPriceSource\":").append(nullableString(e.fillPriceSource))
            append(",\"latencyModel\":").append(nullableString(e.latencyModel))
            append(",\"slippageModel\":").append(nullableString(e.slippageModel))
            append(",\"rejectionModel\":").append(nullableString(e.rejectionModel))
            append(",\"partialFillModel\":").append(nullableString(e.partialFillModel))
            append(",\"venueRules\":").append(nullableString(e.venueRules))
            append(",\"commissionModel\":").append(nullableString(e.commissionModel))
            append(",\"ocoMode\":").append(nullableString(e.ocoMode))
            append(",\"warning\":").append(nullableString(e.warning))
            append("}")
        }

    private fun experiment(e: ExperimentEvidence): String =
        buildString {
            append("{\"id\":").append(nullableString(e.id))
            append(",\"trialCount\":").append(e.trialCount?.toString() ?: "null")
            append(",\"primaryMetric\":").append(nullableString(e.primaryMetric))
            append(",\"splits\":").append(stringMap(e.splits))
            append(",\"selectedLabel\":").append(nullableString(e.selectedLabel))
            append(",\"selectedParams\":").append(stringMap(e.selectedParams))
            append(",\"warnings\":").append(stringList(e.warnings))
            append(",\"warning\":").append(nullableString(e.warning))
            append("}")
        }

    private fun accounting(a: AccountingEvidence): String =
        buildString {
            append("{\"accountCurrency\":").append(nullableString(a.accountCurrency))
            append(",\"missingPolicy\":").append(nullableString(a.missingPolicy))
            append(",\"source\":").append(nullableString(a.source))
            append(",\"configuredFxSymbols\":").append(stringMap(a.configuredFxSymbols))
            append(",\"conversions\":").append(stringMap(a.conversions))
            append(",\"costKinds\":").append(stringList(a.costKinds))
            append(",\"warnings\":").append(stringList(a.warnings))
            append(",\"warning\":").append(nullableString(a.warning))
            append("}")
        }

    private fun promotion(p: PromotionEvidence): String =
        buildString {
            append("{\"state\":").append(nullableString(p.state))
            append(",\"eligibleForProduction\":").append(p.eligibleForProduction?.toString() ?: "null")
            append(",\"missingGates\":").append(stringList(p.missingGates))
            append(",\"rationale\":").append(nullableString(p.rationale))
            append(",\"warning\":").append(nullableString(p.warning))
            append("}")
        }

    private fun stringMap(m: Map<String, String>): String =
        m.entries
            .sortedBy { it.key }
            .joinToString(",", prefix = "{", postfix = "}") {
                "${jsonString(it.key)}:${jsonString(it.value)}"
            }

    private fun stringList(values: List<String>): String =
        values.joinToString(",", prefix = "[", postfix = "]") { jsonString(it) }

    private fun nullableString(value: String?): String = value?.let(::jsonString) ?: "null"

    fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c < ' ') {
                        sb.append("\\u%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
