package com.qkt.cli

import java.nio.file.Path

/** Which promotion gates to require, and whether a failing gate blocks the strategy from running. */
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

private fun String?.toBooleanConfig(default: Boolean): Boolean = this?.toBooleanConfig() ?: default

private fun String.toBooleanConfig(): Boolean? =
    when (trim().lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }
