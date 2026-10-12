package com.qkt.cli

/**
 * The one place per-command help text lives (#1380): usage plus flags, and run introspection.
 * The grouped top-level list lives in [CliTopHelp]. `qkt <command> --help` prints [forCommand];
 * an unknown command with no close match prints the top-level list. Flag descriptions exist
 * for the backtest family ([BacktestFlagHelp]); other commands render their schema flag names
 * until they grow their own descriptions.
 */
internal object CliHelp {
    private val backtestCommands = setOf("backtest", "sweep", "walkforward", "research")

    /** How many flag rows a non-backtest command shows before pointing at `--help --all`. */
    private const val CORE_OTHER = 12

    private val usages =
        mapOf(
            "backtest" to "qkt backtest <strategy.qkt> --from DATE --to DATE [options]",
            "sweep" to "qkt sweep <strategy.qkt> --from DATE --to DATE --param NAME=v1,v2 [options]",
            "walkforward" to "qkt walkforward <strategy.qkt> --from DATE --to DATE [options]",
            "research" to "qkt research <strategy.qkt> --from DATE --to DATE [options]",
        )

    /**
     * Full help for [name]: usage line plus flags. Core only by default (the flags that matter
     * on first contact); [full] adds the rest; [filter] narrows to flags matching one query
     * (`qkt backtest --help from`). Account, currency and costs are config, not flags — the core
     * list points there instead of dumping 50 rows.
     */
    fun forCommand(
        name: String,
        full: Boolean = false,
        filter: String? = null,
    ): String =
        buildString {
            appendLine()
            appendLine("Usage: ${usages[name] ?: "qkt $name [...]"}")
            appendLine()
            val schema = CliOptionSchemas.forSubcommand(name) ?: return@buildString
            val described = name in backtestCommands
            val allHeads = heads(schema)
            val shown =
                when {
                    filter != null -> allHeads.filter { it.contains(filter) }
                    full -> allHeads
                    described -> allHeads.filter { it.name in BacktestFlagHelp.core }
                    else -> allHeads.take(CORE_OTHER)
                }
            if (filter != null && shown.isEmpty()) {
                appendLine("  No flag matching '$filter'.")
                return@buildString
            }
            for (head in shown.sortedBy { it.name }) appendLine("  ${row(head, described)}")
            if (!full && filter == null) {
                val rest = allHeads.size - shown.size
                if (rest > 0) appendLine("  ... and $rest more: qkt $name --help --all")
            }
            if (described && filter == null) {
                appendLine("  Account, currency and costs come from qkt.config.yaml, not flags.")
            }
            if (name in backtestCommands && filter == null) {
                appendLine("  Named runs: qkt $name <run> (see backtest.runs in qkt.config.yaml)")
            }
            val aliases = CliOptionSchemas.forSubcommand(name)?.shortAliases.orEmpty()
            if (aliases.isNotEmpty() && filter == null) {
                appendLine("  Short flags: ${aliases.toSortedMap().map { (s, l) -> "$s = --$l" }.joinToString(", ")}")
            }
        }

    private data class Head(
        val name: String,
        val head: String,
    ) {
        fun contains(q: String): Boolean {
            val query = q.lowercase()
            return name.lowercase().contains(query) || head.lowercase().contains(query)
        }
    }

    private fun heads(schema: CliOptionSchema): List<Head> =
        schema.values.sorted().map { Head(it, "--$it VALUE") } +
            schema.flags.sorted().map { Head(it, "--$it") } +
            schema.optionalValues.sorted().map { Head(it, "--$it [VALUE]") }

    private fun row(
        head: Head,
        described: Boolean,
    ): String {
        if (!described) return head.head
        val desc = BacktestFlagHelp.descriptions[head.name] ?: ""
        return if (desc.isEmpty()) head.head else head.head.padEnd(26) + " " + desc
    }

    /**
     * Help for one configured run: its merged keys with provenance, i.e. what
     * `qkt backtest <name>` would actually use before flags.
     */
    fun forRun(
        name: String,
        merged: BacktestRunSelection.Merged,
    ): String =
        buildString {
            appendLine()
            appendLine("Run '$name' (qkt backtest $name):")
            appendLine()
            for ((key, value) in merged.values.toSortedMap()) {
                val from = merged.provenance[key] ?: "global"
                appendLine("  ${key.padEnd(20)} $value   ($from)")
            }
            if (merged.params.isNotEmpty()) {
                appendLine("  params: ${merged.params.joinToString(", ")}")
            }
            appendLine()
            appendLine("  Flags always win: append any flag to override.")
        }

    /**
     * Suggest the closest subcommand to a mistype, or null when nothing is close. Threshold 2
     * edits per the issue: near-misses resolve, wild guesses fall through to [topLevelHelp].
     */
    fun suggestCommand(typo: String): String? {
        val candidates = CliOptionSchemas.names().filter { !it.startsWith("-") }
        return CliSuggest.suggest(typo, candidates)
    }

    /** Suggest the closest flag of [subcommand] to a mistype, or null when nothing is close. */
    fun suggestFlag(
        subcommand: String,
        typo: String,
    ): String? {
        val schema = CliOptionSchemas.forSubcommand(subcommand) ?: return null
        val candidates = schema.values + schema.flags + schema.optionalValues
        return CliSuggest.suggest(typo.trimStart('-'), candidates)?.let { "--$it" }
    }
}
