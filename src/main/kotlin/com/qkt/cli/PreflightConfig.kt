package com.qkt.cli

import java.nio.file.Path

/** Config loading for [ProductionPreflight]: either the loaded config and its runtime mode, or a FAIL check. */
internal sealed interface PreflightConfig {
    data class Loaded(
        val cfg: Config,
        val runtimeMode: RuntimeMode,
    ) : PreflightConfig

    data class Failed(
        val check: PreflightCheck,
    ) : PreflightConfig
}

/**
 * Load the preflight config. `runtime.mode` is read lazily from the loaded map, so a bad value
 * throws here, not in [Config.load]: both degrade to a FAIL check instead of a stack trace (#1373).
 */
internal fun loadPreflightConfig(configPath: Path): PreflightConfig {
    val cfg =
        try {
            Config.load(configPath)
        } catch (e: Exception) {
            return PreflightConfig.Failed(fail(e))
        }
    val runtimeMode =
        try {
            cfg.runtimeMode
        } catch (e: Exception) {
            return PreflightConfig.Failed(fail(e))
        }
    return PreflightConfig.Loaded(cfg, runtimeMode)
}

private fun fail(e: Exception): PreflightCheck =
    PreflightCheck(
        "config.load",
        PreflightStatus.FAIL,
        e.message ?: e.toString(),
    )
