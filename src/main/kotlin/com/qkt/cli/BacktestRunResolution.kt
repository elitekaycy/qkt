package com.qkt.cli

import com.qkt.evidence.ResolvedValue
import java.nio.file.Path

/**
 * Turns a selection into everything `BacktestCommand` needs: strategy path, CLI-overlaid
 * args, `--param` overrides (config first, CLI wins), and the resolved-values record.
 */
internal object BacktestRunResolution {
data class ResolvedRun(
    val strategy: Path,
    val effective: Args,
    val paramOverrides: Map<String, String>,
    val resolved: Map<String, ResolvedValue>,
    val runName: String?,
)

fun resolve(
    args: Args,
    cfg: Config,
): ResolvedRun {
    val section = cfg.backtest
    // Selection problems are argument problems (Main prints them one-line as ARG_ERROR);
    // malformed values stay SetupError (USER_ERROR with the usual prefix) exactly as before.
    val selection =
        when (val s = BacktestRunSelection.select(args.positional(0), section)) {
            is BacktestRunSelection.Selection.Error -> throw ArgError(s.message)
            else -> s
        }
    val strategyToken: String
    val run: BacktestRun?
    val runName: String?
    val strategyLayer: String
    when (selection) {
        is BacktestRunSelection.Selection.File -> {
            strategyToken = selection.path.toString()
            run = null
            runName = null
            strategyLayer = if (selection.configured) BacktestRunSelection.FROM_GLOBAL else BacktestRunSelection.FROM_FILE
        }
        is BacktestRunSelection.Selection.Named -> {
            run = selection.run
            runName = selection.name
            strategyToken =
                run.strategy ?: section.strategy
                    ?: throw ArgError(
                        "no strategy for run '${selection.name}' and backtest.strategy is unset" +
                            BacktestRunSelection.runListSuffix(section),
                    )
            strategyLayer = if (run.strategy != null) BacktestRunSelection.fromRun(runName) else BacktestRunSelection.FROM_GLOBAL
        }
        is BacktestRunSelection.Selection.Error -> throw ArgError(selection.message)
    }
    val strategy =
        if (selection is BacktestRunSelection.Selection.File && !selection.configured) {
            selection.path
        } else {
            cfg.configDir?.resolve(strategyToken) ?: Path.of(strategyToken)
        }
    val merged: BacktestRunSelection.Merged = BacktestRunSelection.merge(section, run, runName)
    val effective = args.withDefaults(merged.values, mapOf("param" to merged.params))
    val paramOverrides = paramOverrides(args, merged)
    val resolved = recordResolved(args, effective, merged, strategyToken, strategyLayer, paramOverrides)
    return ResolvedRun(strategy, effective, paramOverrides, resolved, runName)
}

    /** `--param` overrides: config first, explicit CLI entries win; commas still mean sweep. */
    private fun paramOverrides(
        args: Args,
        merged: BacktestRunSelection.Merged,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (tok in merged.params) {
            val name = BacktestRunSelection.paramName(tok)
            val value = BacktestRunSelection.paramValue(tok)
            if (value.contains(',')) {
                throw BacktestContext.Companion.SetupError(
                    "multiple values for '$name' in config param; use 'qkt sweep' to grid-search",
                )
            }
            out[name] = value
        }
    for (tok in args.options("param")) {
        val eq = tok.indexOf('=')
        if (eq <= 0) {
            throw BacktestContext.Companion.SetupError("bad --param '$tok'; expected NAME=VALUE")
        }
        val name = tok.substring(0, eq).trim()
        val value = tok.substring(eq + 1).trim()
        if (value.contains(',')) {
            throw BacktestContext.Companion.SetupError(
                "multiple values for '$name'; use 'qkt sweep' to grid-search",
            )
        }
        out[name] = value
    }
    return out
}

/**
 * Per-key resolved record: merged config values with their layer, CLI-set keys marked `flag`,
 * and one `param.<name>` entry per effective param.
 */
private fun recordResolved(
    args: Args,
    effective: Args,
    merged: BacktestRunSelection.Merged,
    strategyToken: String,
    strategyLayer: String,
    paramOverrides: Map<String, String>,
): Map<String, ResolvedValue> {
    val resolved = LinkedHashMap<String, ResolvedValue>()
    resolved["strategy"] = ResolvedValue(strategyToken, strategyLayer)
    for ((k, v) in merged.values) {
        resolved[k] = ResolvedValue(v, merged.provenance[k] ?: BacktestRunSelection.FROM_GLOBAL)
    }
    for ((name, value) in paramOverrides) {
        val layer =
            if (args.options("param").any { BacktestRunSelection.paramName(it) == name }) {
                BacktestRunSelection.FROM_FLAG
            } else {
                merged.paramProvenance[name] ?: BacktestRunSelection.FROM_GLOBAL
            }
        resolved["param.$name"] = ResolvedValue(value, layer)
    }
        val schema = CliOptionSchemas.forSubcommand("backtest")
        val valueKeys = schema?.values.orEmpty() + schema?.optionalValues.orEmpty()
        val flagKeys = schema?.flags.orEmpty()
        for (key in valueKeys + flagKeys) {
            if (key == "param") continue
            // Value options take the following token; boolean flags stand alone. Mixing them
            // up shifts every value onto the wrong key, so the schema decides, not position.
            if (key in valueKeys && args.hasExplicitOption(key)) {
                resolved[key] = ResolvedValue(effective.option(key) ?: "", BacktestRunSelection.FROM_FLAG)
            } else if (key in flagKeys && args.hasExplicitFlag(key)) {
                resolved[key] = ResolvedValue("true", BacktestRunSelection.FROM_FLAG)
            }
        }
        return resolved
    }
}
