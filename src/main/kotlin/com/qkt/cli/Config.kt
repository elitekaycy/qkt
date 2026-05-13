package com.qkt.cli

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Parsed `qkt.config.yaml` — the operator-facing config file.
 *
 * Source-of-truth schema: see [reference/config-schema](https://elitekaycy.github.io/qkt/reference/config-schema/).
 * Env-var references like `${EXNESS_GATEWAY_URL}` are expanded at load time.
 */
data class Config(
    val source: String = "tv",
    val dataRoot: String = "./data",
    val startingBalance: BigDecimal = BigDecimal.ZERO,
    val logLevel: String = "info",
    val tv: Map<String, String> = emptyMap(),
    val fetchers: Map<String, Map<String, String>> = emptyMap(),
    val brokers: Map<String, Map<String, String>> = emptyMap(),
    /**
     * Daemon-level risk knobs. Currently honored:
     *   - `max_daily_loss` — global daily-loss cap in account currency (BigDecimal).
     *     Defaults to [DEFAULT_MAX_DAILY_LOSS] when unset. Set to "0" to disable.
     *
     * Per-strategy risk DSL is a separate, larger surface (Phase 28+); for now every
     * strategy the daemon hosts shares these limits.
     */
    val risk: Map<String, String> = emptyMap(),
    /**
     * Engine state persistence settings (Phase 29). Knobs:
     *   - `enabled` — `true` (default) wires [com.qkt.persistence.FileStatePersistor]; `false`
     *     uses [com.qkt.persistence.NoopStatePersistor] (no disk I/O, no restart recovery).
     *   - `dir` — root directory for state files. `~/` is expanded against `$HOME`.
     *     Default: `~/.qkt/state`.
     */
    val state: Map<String, String> = emptyMap(),
) {
    /**
     * Effective `max_daily_loss` from [risk], or [DEFAULT_MAX_DAILY_LOSS]. Operators set
     * `risk.max_daily_loss: 0` to disable the halt rule entirely.
     */
    val maxDailyLoss: BigDecimal
        get() = risk["max_daily_loss"]?.let(::BigDecimal) ?: DEFAULT_MAX_DAILY_LOSS

    /** Effective `state.enabled` from config; defaults to `true`. */
    val stateEnabled: Boolean
        get() = state["enabled"]?.lowercase()?.let { it != "false" } ?: true

    /** Effective `state.dir`; defaults to `~/.qkt/state`. `~/` expanded against `$HOME`. */
    val stateDir: String
        get() = (state["dir"] ?: "~/.qkt/state").replaceFirst("~/", System.getProperty("user.home") + "/")

    /**
     * Returns the [com.qkt.persistence.StatePersistor] for this config. `NoopStatePersistor`
     * if `state.enabled = false`; `FileStatePersistor` rooted at [stateDir] otherwise.
     */
    fun statePersistor(): com.qkt.persistence.StatePersistor =
        if (!stateEnabled) {
            com.qkt.persistence.NoopStatePersistor()
        } else {
            com.qkt.persistence.FileStatePersistor(
                java.nio.file.Path
                    .of(stateDir),
            )
        }

    companion object {
        /**
         * Conservative default for `risk.max_daily_loss` when the config doesn't set one.
         * Live daemons that hit this halt all strategies for the rest of the trading day.
         * Tuned for a small starting balance — operators with bigger accounts should set
         * an explicit `risk.max_daily_loss` in `qkt.config.yaml`.
         */
        val DEFAULT_MAX_DAILY_LOSS: BigDecimal = BigDecimal("1000")

        // Matches `${NAME}` and `${NAME:-default}`. The default is used when the env
        // var / system property is unset; without it, the literal `${...}` stays in the
        // string and broker init logs a degraded-config warning.
        private val varRegex = Regex("\\$\\{([A-Z_][A-Z_0-9]*)(?::-([^}]*))?}")

        /** Reads the YAML at [path] and parses it. Returns built-in defaults if the file is missing. */
        fun load(path: Path): Config {
            if (!Files.exists(path)) return defaults()
            val raw = Files.readString(path)
            val expanded = expandVars(raw)

            @Suppress("UNCHECKED_CAST")
            val map =
                Load(LoadSettings.builder().build())
                    .loadFromString(expanded) as? Map<String, Any?>
                    ?: return defaults()
            return Config(
                source = (map["source"] as? String) ?: "tv",
                dataRoot = (map["data_root"] as? String) ?: "./data",
                startingBalance =
                    (map["starting_balance"]?.toString() ?: "0").let(::BigDecimal),
                logLevel = (map["log_level"] as? String) ?: "info",
                tv = parseFlat(map["tv"]),
                fetchers = parseNested(map["fetchers"]),
                brokers = parseNested(map["brokers"]),
                risk = parseFlat(map["risk"]),
                state = parseFlat(map["state"]),
            )
        }

        private fun defaults(): Config =
            Config(
                source = "local",
                dataRoot = "./data",
                startingBalance = BigDecimal.ZERO,
            )

        @Suppress("UNCHECKED_CAST")
        private fun parseFlat(raw: Any?): Map<String, String> {
            val m = raw as? Map<String, Any?> ?: return emptyMap()
            return m.mapValues { (_, v) -> v?.toString() ?: "" }
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseNested(raw: Any?): Map<String, Map<String, String>> {
            val outer = raw as? Map<String, Any?> ?: return emptyMap()
            return outer.mapValues { (_, v) ->
                (v as? Map<String, Any?> ?: emptyMap())
                    .mapValues { (_, vv) -> vv?.toString() ?: "" }
            }
        }

        private fun expandVars(s: String): String =
            varRegex.replace(s) { m ->
                val name = m.groupValues[1]
                val default = m.groups[2]?.value
                System.getenv(name) ?: System.getProperty(name) ?: default ?: m.value
            }
    }
}
