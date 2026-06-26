package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class PromotionState(
    val id: String,
) {
    @SerialName("draft")
    DRAFT("draft"),

    @SerialName("research")
    RESEARCH("research"),

    @SerialName("candidate")
    CANDIDATE("candidate"),

    @SerialName("paper")
    PAPER("paper"),

    @SerialName("shadow-live")
    SHADOW_LIVE("shadow-live"),

    @SerialName("small-capital")
    SMALL_CAPITAL("small-capital"),

    @SerialName("production")
    PRODUCTION("production"),

    @SerialName("retired")
    RETIRED("retired"),
    ;

    fun atLeast(required: PromotionState): Boolean = rank >= required.rank

    private val rank: Int
        get() =
            when (this) {
                DRAFT -> 0
                RESEARCH -> 1
                CANDIDATE -> 2
                PAPER -> 3
                SHADOW_LIVE -> 4
                SMALL_CAPITAL -> 5
                PRODUCTION -> 6
                RETIRED -> -1
            }

    companion object {
        fun fromId(raw: String?): PromotionState? {
            val normalized =
                raw
                    ?.trim()
                    ?.lowercase()
                    ?.replace('_', '-')
                    ?: return null
            return entries.firstOrNull { it.id == normalized }
        }
    }
}

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

@Serializable
data class PromotionApproval(
    val state: PromotionState,
    val actor: String,
    val reason: String,
    val approvedAt: String,
)

@Serializable
data class PromotionWaiver(
    val gates: List<String>,
    val reason: String,
    val actor: String,
    val createdAt: String,
    val expiresAt: String? = null,
) {
    fun active(now: Instant = Instant.now()): Boolean =
        expiresAt
            ?.let { runCatching { Instant.parse(it).isAfter(now) }.getOrDefault(false) }
            ?: true

    fun waives(gate: String): Boolean {
        val normalized = gates.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        if ("all" in normalized) return true
        val candidate = gate.lowercase()
        return candidate in normalized || candidate.substringBefore(':') in normalized
    }
}

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

object PromotionJson {
    val format: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    fun encode(record: PromotionRecord): String = format.encodeToString(record)

    fun encode(result: PromotionGateResult): String = format.encodeToString(result)
}

class PromotionStore(
    private val root: Path,
) {
    private val file: Path = root.resolve("promotions.jsonl")

    fun append(record: PromotionRecord): PromotionRecord {
        Files.createDirectories(root)
        Files.writeString(
            file,
            PromotionJson.encode(record) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        return record
    }

    fun all(): List<PromotionRecord> {
        if (!Files.exists(file)) return emptyList()
        return Files
            .readAllLines(file)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { PromotionJson.format.decodeFromString<PromotionRecord>(it) }
            .toList()
    }

    fun latest(strategy: String): PromotionRecord? =
        all()
            .asSequence()
            .filter { it.strategy == strategy }
            .maxByOrNull { Instant.parse(it.updatedAt) }

    fun latest(
        strategy: String,
        strategyHash: String,
    ): PromotionRecord? =
        all()
            .asSequence()
            .filter { it.strategy == strategy && it.strategyHash == strategyHash }
            .maxByOrNull { Instant.parse(it.updatedAt) }

    companion object {
        fun defaultRoot(): Path = UserDirs().stateHome().resolve("state").resolve("promotion")
    }
}

data class PromotionGateConfig(
    val enforce: Boolean = false,
    val requiredState: PromotionState = PromotionState.PRODUCTION,
    val requireDatasetSnapshot: Boolean = false,
    val requireRealisticExecution: Boolean = false,
    val requireWalkForward: Boolean = false,
    val requireApproval: Boolean = true,
    val minPaperDays: Int = 0,
    val minPaperTrades: Int = 0,
    val maxPaperSlippageBps: Double? = null,
    val registryDir: Path? = null,
) {
    companion object {
        val DISABLED: PromotionGateConfig = PromotionGateConfig()

        fun fromConfig(
            raw: Map<String, String>,
            runtimeMode: RuntimeMode,
        ): PromotionGateConfig {
            val enforce = raw["enforce"]?.toBooleanConfig() ?: runtimeMode.production
            return PromotionGateConfig(
                enforce = enforce,
                requiredState = PromotionState.fromId(raw["required_state"]) ?: PromotionState.PRODUCTION,
                requireDatasetSnapshot = raw["dataset_snapshot"].toBooleanConfig(default = false),
                requireRealisticExecution = raw["realistic_execution"].toBooleanConfig(default = false),
                requireWalkForward = raw["walk_forward"].toBooleanConfig(default = false),
                requireApproval = raw["approval"].toBooleanConfig(default = true),
                minPaperDays = raw["paper_days"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                minPaperTrades = raw["paper_min_trades"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                maxPaperSlippageBps = raw["max_paper_slippage_bps"]?.toDoubleOrNull(),
                registryDir = raw["registry_dir"]?.takeIf { it.isNotBlank() }?.let(Path::of),
            )
        }
    }
}

class PromotionGateEvaluator(
    private val config: PromotionGateConfig,
) {
    fun evaluate(
        strategy: String,
        strategyPath: Path?,
        store: PromotionStore,
        extraWaivers: List<PromotionWaiver> = emptyList(),
        now: Instant = Instant.now(),
    ): PromotionGateResult {
        val strategyHash = strategyPath?.let { strategyHash(it) }
        val record =
            when {
                strategyHash != null -> store.latest(strategy, strategyHash) ?: store.latest(strategy)
                else -> store.latest(strategy)
            }
        val missing = mutableListOf<String>()
        if (record == null) {
            missing.add("promotion_record")
        } else {
            if (strategyHash != null && record.strategyHash != strategyHash) missing.add("strategy_hash")
            if (!record.state.atLeast(config.requiredState)) missing.add("state:${config.requiredState.id}")
            if (config.requireApproval && !record.approvedFor(config.requiredState)) missing.add("operator_approval")
            if (config.requireDatasetSnapshot && record.evidence["dataset_snapshot"].isNullOrBlank()) {
                missing.add("dataset_snapshot")
            }
            if (config.requireRealisticExecution && record.evidence["realistic_execution"].isNullOrBlank()) {
                missing.add("realistic_execution")
            }
            if (config.requireWalkForward && record.evidence["walk_forward"].isNullOrBlank()) {
                missing.add("walk_forward")
            }
            val paper = record.paper
            if (config.minPaperDays > 0 && (paper?.days ?: 0) < config.minPaperDays) {
                missing.add("paper_days")
            }
            if (config.minPaperTrades > 0 && (paper?.trades ?: 0) < config.minPaperTrades) {
                missing.add("paper_min_trades")
            }
            val maxSlip = config.maxPaperSlippageBps
            val p95Slip = paper?.p95SlippageBps
            if (maxSlip != null && (p95Slip == null || p95Slip > maxSlip)) {
                missing.add("paper_slippage")
            }
            if (paper?.status?.equals("fail", ignoreCase = true) == true) {
                missing.add("paper_status")
            }
        }

        val activeWaivers =
            (record?.waivers.orEmpty() + extraWaivers)
                .filter { it.active(now) && it.reason.isNotBlank() }
        val waived =
            missing
                .filter { gate -> activeWaivers.any { it.waives(gate) } }
                .distinct()
        val effectiveMissing = missing.filterNot { it in waived }.distinct()
        val warnings =
            buildList {
                if (waived.isNotEmpty()) add("waived gates: ${waived.joinToString(",")}")
            }

        return PromotionGateResult(
            strategy = strategy,
            state = record?.state?.id,
            recordId = record?.id,
            strategyHash = strategyHash ?: record?.strategyHash,
            paper = record?.paper,
            requiredState = config.requiredState.id,
            enforced = config.enforce,
            eligibleForProduction = effectiveMissing.isEmpty(),
            missingGates = effectiveMissing,
            waivedGates = waived,
            warnings = warnings,
        )
    }

    companion object {
        fun strategyHash(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            return "sha256:" + digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String {
            val out = StringBuilder(size * 2)
            for (b in this) out.append("%02x".format(b))
            return out.toString()
        }
    }
}

private fun String?.toBooleanConfig(default: Boolean): Boolean = this?.toBooleanConfig() ?: default

private fun String.toBooleanConfig(): Boolean? =
    when (trim().lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }
