package com.qkt.cli

import com.qkt.evidence.ResolvedValue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves what `qkt backtest [token]` runs: an explicit file, a named run from
 * `backtest.runs:`, or the global `backtest:` block — then merges the layers into effective
 * defaults (flag > run > global > built-in) and records per-key provenance. Pure logic;
 * filesystem access is injected for tests.
 */
internal object BacktestRunSelection {
    /** Where one merged key came from, for the resolved-values record. */
    const val FROM_FLAG = "flag"

    fun fromRun(name: String): String = "run:$name"

    const val FROM_GLOBAL = "global"

    const val FROM_FILE = "file"

    /** The selected strategy source: a file on disk or a configured run. */
    sealed interface Selection {
        /** [configured] is true for config-sourced paths (resolved against the config dir). */
        data class File(
            val path: Path,
            val configured: Boolean = false,
        ) : Selection

        data class Named(
            val name: String,
            val run: BacktestRun,
        ) : Selection

        /** Fatal selection problem; message already lists runs and suggestions. */
        data class Error(val message: String) : Selection
    }

    /** Effective defaults for [Args.withDefaults]: merged values, params, and per-key provenance. */
    data class Merged(
        val values: Map<String, String> = emptyMap(),
        val params: List<String> = emptyList(),
        val provenance: Map<String, String> = emptyMap(),
        val paramProvenance: Map<String, String> = emptyMap(),
    )

    /**
     * Everything `BacktestCommand` needs: the strategy path, CLI-overlaid args, `--param`
     * overrides (config first, CLI wins), and the resolved-values record. Throws
     * [BacktestContext.Companion.SetupError] on selection problems.
     */
    private fun missingStrategy(section: BacktestSection): String =
        "no strategy given: pass a file, name a run, or set backtest.strategy" + runListSuffix(section)

    private fun unknownRun(
        token: String,
        section: BacktestSection,
    ): String {
        val hint = CliSuggest.suggest(token, section.runs.keys)?.let { ". Did you mean '$it'?" } ?: ""
        return "no file '$token' and no run '$token'$hint" + runListSuffix(section)
    }

    internal fun runListSuffix(section: BacktestSection): String =
        if (section.runs.isEmpty()) {
            ""
        } else {
            "\n  runs: ${section.runs.keys.sorted().joinToString(", ")}"
        }
    fun select(
        token: String?,
        section: BacktestSection,
        fileExists: (String) -> Boolean = { Files.exists(Path.of(it)) },
    ): Selection {
        if (token == null) {
            if (section.strategy != null) return Selection.File(Path.of(section.strategy), configured = true)
            // No backtest: block at all: today's missing-argument error, plus usage (#1380).
            if (section == BacktestSection()) {
                return Selection.Error("missing required argument: <strategy.qkt>${CliHelp.forCommand("backtest")}")
            }
            return Selection.Error(missingStrategy(section))
        }
        if (fileExists(token)) return Selection.File(Path.of(token))
        val base = token.removeSuffix(".qkt")
        val runName = if (section.runs.containsKey(base)) base else token
        val run = section.runs[runName]
        if (run != null) return Selection.Named(runName, run)
        if (!token.endsWith(".qkt")) return Selection.Error(unknownRun(token, section))
        // A typed path that names no file and no run: let the caller's file check report it
        // exactly as before (same message, same exit code).
        return Selection.File(Path.of(token))
    }

    internal fun paramName(tok: String): String = tok.substringBefore('=').trim()

    internal fun paramValue(tok: String): String = tok.substringAfter('=', "").trim()

    /** Merge global and (optional) named-run maps; named wins. Provenance tracks each key. */
    fun merge(
        section: BacktestSection,
        run: BacktestRun?,
        runName: String?,
    ): Merged {
        val values = LinkedHashMap(section.defaults)
        val provenance = LinkedHashMap(section.defaults.mapValues { FROM_GLOBAL })
        val paramProvenance = LinkedHashMap<String, String>()
        for (p in section.defaultParams) paramProvenance[paramName(p)] = FROM_GLOBAL
        if (run != null) {
            for ((k, v) in run.values) {
                values[k] = v
                provenance[k] = fromRun(runName ?: "?")
            }
            for (p in run.params) paramProvenance[paramName(p)] = fromRun(runName ?: "?")
        }
        val params = section.defaultParams + (run?.params.orEmpty())
        return Merged(values, params, provenance, paramProvenance)
    }

}
