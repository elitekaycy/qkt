package com.qkt.cli

/**
 * The `backtest:` (and per-run) section of `qkt.config.yaml`: repeatable project-local backtests.
 * Everything is optional and stringly-typed — keys are backtest flag names (`from:`, `no-fetch:
 * true`, `param: {fast: 5}`), so CLI flags, run maps and the global map all speak one language.
 * Unknown keys warn once and never fail (#1376 strictness stays out of config files).
 */
data class BacktestSection(
    /** Global defaults under `backtest:` (excluding `runs:` and `strategy:`). */
    val defaults: Map<String, String> = emptyMap(),
    /** Repeatable `--param NAME=VALUE` entries under `backtest:`. */
    val defaultParams: List<String> = emptyList(),
    /** Default strategy file for bare `qkt backtest`, or null when the project names none. */
    val strategy: String? = null,
    /** Named runs under `backtest.runs:`: run name to its key map. */
    val runs: Map<String, BacktestRun> = emptyMap(),
    /** Unknown keys met while parsing, for one-time warnings. */
    val warnings: List<String> = emptyList(),
)

/** One named run under `backtest.runs:`: its own keys, inheriting the global map beneath. */
data class BacktestRun(
    val values: Map<String, String> = emptyMap(),
    val params: List<String> = emptyList(),
    val strategy: String? = null,
)

internal object BacktestRunConfig {
    /** Flag names the backtest command accepts — anything else warns as a likely typo. */
    private fun knownKeys(): Set<String> {
        val schema = CliOptionSchemas.forSubcommand("backtest")
        return (schema?.values.orEmpty() + schema?.flags.orEmpty() + schema?.optionalValues.orEmpty())
    }

    /** Parse the raw `backtest:` map (or null when absent). Never throws on shape. */
    fun parse(raw: Any?): BacktestSection {
        val map = raw as? Map<String, Any?> ?: return BacktestSection()
        val known = knownKeys()
        val warnings = mutableListOf<String>()
        val defaults = mutableMapOf<String, String>()
        val defaultParams = mutableListOf<String>()
        var strategy: String? = null
        val runs = mutableMapOf<String, BacktestRun>()
        for ((key, value) in map) {
            when (key) {
                "strategy" -> strategy = value?.toString()
                "runs" -> runs.putAll(parseRuns(value, warnings, known))
                "param" -> defaultParams += parseParams(value, "backtest.param", warnings)
                else ->
                    if (value is Map<*, *> || value is List<*>) {
                        warnings += "backtest.$key: expected a scalar value, ignoring"
                    } else if (value != null) {
                        if (key !in known) warnings += "backtest.$key: unknown key, using as-is"
                        defaults[key] = value.toString()
                    }
            }
        }
        return BacktestSection(defaults, defaultParams, strategy, runs, warnings)
    }
    private fun parseRuns(
        raw: Any?,
        warnings: MutableList<String>,
        known: Set<String>,
    ): Map<String, BacktestRun> {
        val map = raw as? Map<String, Any?> ?: return emptyMap<String, BacktestRun>().also {
            warnings += "backtest.runs: expected a map of run names, ignoring"
        }
        return map.mapNotNull { (name, value) ->
            val runMap = value as? Map<String, Any?> ?: return@mapNotNull null.also {
                warnings += "backtest.runs.$name: expected a map, ignoring"
            }
            val values = mutableMapOf<String, String>()
            val params = mutableListOf<String>()
            var strategy: String? = null
            for ((key, v) in runMap) {
                when (key) {
                    "strategy" -> strategy = v?.toString()
                    "param" -> params += parseParams(v, "backtest.runs.$name.param", warnings)
                    else ->
                        if (v is Map<*, *> || v is List<*>) {
                            warnings += "backtest.runs.$name.$key: expected a scalar value, ignoring"
                        } else if (v != null) {
                            if (key !in known) warnings += "backtest.runs.$name.$key: unknown key, using as-is"
                            values[key] = v.toString()
                        }
                }
            }
            name.toString() to BacktestRun(values, params, strategy)
        }.toMap()
    }

    private fun parseParams(
        raw: Any?,
        where: String,
        warnings: MutableList<String>,
    ): List<String> {
        val map = raw as? Map<String, Any?> ?: return emptyList<String>().also {
            warnings += "$where: expected a map like {fast: 5}, ignoring"
        }
        return map.map { (k, v) -> "${k.toString().trim()}=${v.toString().trim()}" }
    }

    /** True string for YAML booleans (`no-fetch: true`); everything else is taken literally. */
    fun isTruthy(raw: String): Boolean = raw.trim().lowercase() in setOf("true", "yes", "on", "1")
}
