package com.qkt.cli

import com.qkt.notify.NotifyConfig
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
     */
    val risk: Map<String, String> = emptyMap(),
    /**
     * Phase 25D: per-strategy risk overrides keyed by strategy name. Each entry sets
     * additional caps that apply only to that strategy — see [PerStrategyRisk]. The
     * global [risk] block still applies daemon-wide; per-strategy caps layer on top.
     */
    val perStrategyRisk: Map<String, PerStrategyRisk> = emptyMap(),
    /**
     * Engine state persistence settings (Phase 29). Knobs:
     *   - `enabled` — `true` (default) wires [com.qkt.persistence.FileStatePersistor]; `false`
     *     uses [com.qkt.persistence.NoopStatePersistor] (no disk I/O, no restart recovery).
     *   - `async` — see [stateAsync].
     *
     * The state directory is not set here: [statePersistor] receives its root from the
     * caller. The daemon passes [com.qkt.cli.daemon.StateDir.stateRoot], which honors
     * `QKT_STATE_DIR` (and `--state-dir`), so state lands on the operator's volume.
     */
    val state: Map<String, String> = emptyMap(),
    /**
     * Outbound alert channels. Defaults to [NotifyConfig.DISABLED]. Each channel block (e.g.
     * `notify.telegram`) is turned on with `enabled: true` plus that channel's credentials, and
     * receives critical-event and daily-summary messages. Telegram is the built-in channel.
     */
    val notify: NotifyConfig = NotifyConfig.DISABLED,
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

    /**
     * Effective `state.async`. When `true`, wraps the file persistor in [com.qkt.persistence.AsyncStatePersistor]
     * so disk I/O runs on a background thread instead of the bus dispatch thread. Defaults to
     * `false` — synchronous is fine for typical trade-event rates.
     */
    val stateAsync: Boolean
        get() = state["async"]?.lowercase() == "true"

    /**
     * Returns the [com.qkt.persistence.StatePersistor] for this config, writing under
     * [stateRoot]. Layered by flag:
     *   - `state.enabled = false`              → [com.qkt.persistence.NoopStatePersistor].
     *   - `state.enabled = true`, `async = false` → [com.qkt.persistence.FileStatePersistor] (synchronous).
     *   - `state.enabled = true`, `async = true`  → [com.qkt.persistence.AsyncStatePersistor] wrapping [com.qkt.persistence.FileStatePersistor].
     *
     * @param stateRoot directory the file persistor writes under (`<stateRoot>/<strategyId>/...`).
     */
    fun statePersistor(stateRoot: Path): com.qkt.persistence.StatePersistor {
        if (!stateEnabled) return com.qkt.persistence.NoopStatePersistor()
        val file = com.qkt.persistence.FileStatePersistor(stateRoot)
        return if (stateAsync) com.qkt.persistence.AsyncStatePersistor(file) else file
    }

    companion object {
        /**
         * Conservative default for `risk.max_daily_loss` when the config doesn't set one.
         * Live daemons that hit this halt all strategies for the rest of the trading day.
         * Tuned for a small starting balance — operators with bigger accounts should set
         * an explicit `risk.max_daily_loss` in `qkt.config.yaml`.
         */
        val DEFAULT_MAX_DAILY_LOSS: BigDecimal = BigDecimal("1000")

        /**
         * Standard locations qkt commands look for `qkt.config.yaml` when no explicit
         * `--config` is passed. Order is meaningful:
         *  1. `./qkt.config.yaml` — local-dev convenience, matches the historical hard-coded default.
         *  2. `/etc/qkt/qkt.config.yaml` — the container-standard location (the qkt-prod compose
         *     image mounts the operator's config here).
         *  3. `~/.qkt/qkt.config.yaml` — per-user config for non-container deployments.
         */
        fun defaultSearchPaths(
            userDirs: UserDirs = UserDirs(),
            home: Path = Path.of(System.getProperty("user.home")),
        ): List<Path> {
            val paths = mutableListOf(Path.of("./qkt.config.yaml"))
            if (!userDirs.isWindows) paths += Path.of("/etc/qkt/qkt.config.yaml")
            paths += userDirs.configHome().resolve("qkt.config.yaml")
            paths += home.resolve(".qkt").resolve("qkt.config.yaml")
            return paths
        }

        /**
         * Return the first existing file from [searchPaths], or null if none exist.
         * One-shot CLI commands that need a real config (brokers, audit-ticks) call this
         * and fail loud on null. The daemon and run commands tolerate a missing config and
         * fall back to defaults via [load].
         */
        fun locate(searchPaths: List<Path> = defaultSearchPaths()): Path? = searchPaths.firstOrNull { Files.exists(it) }

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
                perStrategyRisk = parsePerStrategyRisk(map["risk"]),
                state = parseFlat(map["state"]),
                notify = NotifyConfig.parse(map["notify"]),
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

        @Suppress("UNCHECKED_CAST")
        private fun parsePerStrategyRisk(raw: Any?): Map<String, PerStrategyRisk> {
            val risk = raw as? Map<String, Any?> ?: return emptyMap()
            val perStrat = risk["per_strategy"] as? Map<String, Any?> ?: return emptyMap()
            return perStrat.mapValues { (_, v) ->
                val m = v as? Map<String, Any?> ?: return@mapValues PerStrategyRisk()
                PerStrategyRisk(
                    maxDailyLoss = m["max_daily_loss"]?.toString()?.let(::BigDecimal),
                    maxPositionSize = m["max_position_size"]?.toString()?.let(::BigDecimal),
                    maxOpenPositions = m["max_open_positions"]?.toString()?.toIntOrNull(),
                )
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
