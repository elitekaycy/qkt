package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/** Checks a strategy's latest promotion record against a [PromotionGateConfig], applying active waivers. */
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
