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
     * Per-broker nested config blocks the flat [brokers] reader can't carry. Keyed by broker name.
     * [brokerCalendars] = ordered `(symbolPattern, calendarName)` rules (per-symbol session
     * calendar); [brokerAliases] = `(qktSymbol, brokerSymbol)`; [brokerCapabilityRestrictions] =
     * disabled order-type capability names; [brokerInstrumentOverrides] = `symbol → (field →
     * value)` venue specs. All feed [com.qkt.broker.mt5.MT5BrokerProfileLoader.load].
     */
    val brokerCalendars: Map<String, List<Pair<String, String>>> = emptyMap(),
    val brokerAliases: Map<String, Map<String, String>> = emptyMap(),
    val brokerCapabilityRestrictions: Map<String, List<String>> = emptyMap(),
    val brokerInstrumentOverrides: Map<String, Map<String, Map<String, String>>> = emptyMap(),
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

    /**
     * Per-order quantity cap (`risk.max_order_qty`), mandatory with a shipped default —
     * the FIA §1.1 backstop a sizing bug cannot talk its way past. Always on.
     */
    val maxOrderQty: BigDecimal
        get() = risk["max_order_qty"]?.let(::BigDecimal) ?: DEFAULT_MAX_ORDER_QTY

    /** Per-order notional cap in account currency (`risk.max_order_notional`); always on. */
    val maxOrderNotional: BigDecimal
        get() = risk["max_order_notional"]?.let(::BigDecimal) ?: DEFAULT_MAX_ORDER_NOTIONAL

    /**
     * Price collar (`risk.price_collar_pct`, percent): an order's explicit price must be
     * within this band of the last seen market price (MiFID II RTS 6). Always on.
     */
    val priceCollarFrac: BigDecimal
        get() =
            pctFraction(risk["price_collar_pct"])
                ?: DEFAULT_PRICE_COLLAR_FRAC

    /** Pre-entry margin floor percent (`risk.margin_floor_pct`); 0 disables. Default 200. */
    val marginFloorPct: BigDecimal
        get() = risk["margin_floor_pct"]?.let(::BigDecimal) ?: BigDecimal("200")

    /**
     * Measured-usage window in hours (`risk.measured_usage_hours`): after every daemon
     * deploy, entries above `risk.measured_usage_max_qty` reject for this long. Opt out
     * with an explicit 0 — fresh code meeting production at full size is the Knight
     * pattern. Default 24.
     */
    val measuredUsageHours: Long
        get() = risk["measured_usage_hours"]?.toLongOrNull() ?: 24L

    /** Per-order cap during the measured-usage window. Default 0.01 (venue minimum). */
    val measuredUsageMaxQty: BigDecimal
        get() = risk["measured_usage_max_qty"]?.let(::BigDecimal) ?: BigDecimal("0.01")

    /** Total-drawdown halt threshold as a fraction (config `max_drawdown_pct` is a percent), or null if unset. */
    val maxDrawdownPct: BigDecimal?
        get() = pctFraction(risk["max_drawdown_pct"])

    /** Daily-drawdown halt threshold as a fraction (config `max_daily_drawdown_pct` is a percent), or null. */
    val maxDailyDrawdownPct: BigDecimal?
        get() = pctFraction(risk["max_daily_drawdown_pct"])

    /** Total-drawdown basis (`total_dd_basis`); defaults to static (from initial balance). */
    val totalDdBasis: com.qkt.risk.DrawdownBasis
        get() =
            com.qkt.risk.DrawdownBasis
                .fromConfig(risk["total_dd_basis"])

    /** Daily-drawdown day-start reference basis (`daily_dd_basis`); defaults to balance. */
    val dailyDdBasis: com.qkt.risk.DailyDrawdownBasis
        get() =
            com.qkt.risk.DailyDrawdownBasis
                .fromConfig(risk["daily_dd_basis"])

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

        /** Default per-order quantity cap; see [com.qkt.risk.rules.PreTradeControls]. */
        val DEFAULT_MAX_ORDER_QTY: BigDecimal =
            com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY

        /** Default per-order notional cap; see [com.qkt.risk.rules.PreTradeControls]. */
        val DEFAULT_MAX_ORDER_NOTIONAL: BigDecimal =
            com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL

        /** Default price collar fraction; see [com.qkt.risk.rules.PreTradeControls]. */
        val DEFAULT_PRICE_COLLAR_FRAC: BigDecimal =
            com.qkt.risk.rules.PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC

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
            if (!userDirs.isWindows) paths.add(Path.of("/etc/qkt/qkt.config.yaml"))
            paths.add(userDirs.configHome().resolve("qkt.config.yaml"))
            paths.add(home.resolve(".qkt").resolve("qkt.config.yaml"))
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
                brokerCalendars = parseBrokerCalendars(map["brokers"]),
                brokerAliases = parseBrokerStringMap(map["brokers"], "aliases"),
                brokerCapabilityRestrictions = parseBrokerStringList(map["brokers"], "capability_restrictions"),
                brokerInstrumentOverrides = parseBrokerInstrumentOverrides(map["brokers"]),
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

        /**
         * Ordered per-symbol calendar rules per broker: `brokers.<name>.calendars` is a YAML map
         * `pattern: calendarName`. SnakeYAML preserves insertion order, so first-match-wins is
         * well-defined. e.g. `{ "BTC*": crypto, "*": fx }` → `[(BTC*, crypto), (*, fx)]`.
         */
        @Suppress("UNCHECKED_CAST")
        private fun parseBrokerCalendars(raw: Any?): Map<String, List<Pair<String, String>>> {
            val brokers = raw as? Map<String, Any?> ?: return emptyMap()
            return brokers
                .mapNotNull { (name, cfg) ->
                    val block =
                        (cfg as? Map<String, Any?>)?.get("calendars") as? Map<String, Any?> ?: return@mapNotNull null
                    name to block.map { (pattern, cal) -> pattern to (cal?.toString() ?: "") }
                }.toMap()
        }

        /** A nested `string → string` block under each broker (e.g. `aliases`). */
        @Suppress("UNCHECKED_CAST")
        private fun parseBrokerStringMap(
            raw: Any?,
            key: String,
        ): Map<String, Map<String, String>> {
            val brokers = raw as? Map<String, Any?> ?: return emptyMap()
            return brokers
                .mapNotNull { (name, cfg) ->
                    val block = (cfg as? Map<String, Any?>)?.get(key) as? Map<String, Any?> ?: return@mapNotNull null
                    name to block.mapValues { (_, v) -> v?.toString() ?: "" }
                }.toMap()
        }

        /** A nested list block under each broker (e.g. `capability_restrictions`). */
        @Suppress("UNCHECKED_CAST")
        private fun parseBrokerStringList(
            raw: Any?,
            key: String,
        ): Map<String, List<String>> {
            val brokers = raw as? Map<String, Any?> ?: return emptyMap()
            return brokers
                .mapNotNull { (name, cfg) ->
                    val block = (cfg as? Map<String, Any?>)?.get(key) as? List<Any?> ?: return@mapNotNull null
                    name to block.map { it?.toString() ?: "" }
                }.toMap()
        }

        /** `brokers.<name>.instrument_overrides` = `symbol → (field → value)`. */
        @Suppress("UNCHECKED_CAST")
        private fun parseBrokerInstrumentOverrides(raw: Any?): Map<String, Map<String, Map<String, String>>> {
            val brokers = raw as? Map<String, Any?> ?: return emptyMap()
            return brokers
                .mapNotNull { (name, cfg) ->
                    val block =
                        (cfg as? Map<String, Any?>)?.get("instrument_overrides") as? Map<String, Any?>
                            ?: return@mapNotNull null
                    name to
                        block.mapValues { (_, spec) ->
                            (spec as? Map<String, Any?> ?: emptyMap()).mapValues { (_, v) -> v?.toString() ?: "" }
                        }
                }.toMap()
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
                    maxDrawdownPct = pctFraction(m["max_drawdown_pct"]?.toString()),
                    maxDailyDrawdownPct = pctFraction(m["max_daily_drawdown_pct"]?.toString()),
                )
            }
        }

        /** Parse a percent string (e.g. "8") into a fraction (0.08), validated to `(0, 100]`. Null passes through. */
        fun pctFraction(raw: String?): BigDecimal? {
            if (raw == null) return null
            val pct = BigDecimal(raw)
            require(pct.signum() > 0 && pct <= BigDecimal(100)) { "drawdown pct must be in (0, 100]: $raw" }
            return pct.divide(BigDecimal(100), java.math.MathContext.DECIMAL64)
        }

        private fun expandVars(s: String): String =
            varRegex.replace(s) { m ->
                val name = m.groupValues[1]
                val default = m.groups[2]?.value
                System.getenv(name) ?: System.getProperty(name) ?: default ?: m.value
            }
    }
}
