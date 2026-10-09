package com.qkt.cli

/**
 * The one place command help text lives (#1380): per-command usage plus flags, and the grouped
 * top-level command list. `qkt <command> --help` prints [forCommand]; an unknown command with no
 * close match prints [topLevelHelp]. Flag descriptions exist for the backtest family
 * ([BacktestFlagHelp]); other commands render their schema flag names until they grow their own
 * descriptions.
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

    val topLevelHelp: String =
        """
        qkt — Kotlin trading-strategy DSL runtime

        USAGE
            qkt <subcommand> [arguments]

        PROJECT SCAFFOLDING
            create template <path>  scaffold a project
                                    (--kind mt5|mt5-ci|backtest|portfolio|minimal|bybit|bot)

        STRATEGY AUTHORING
            parse <file>            parse and validate a .qkt file
            dsl vocabulary [--json] list every keyword, indicator, function, field and member
            lsp                     run the language server over stdio (for editors)
            backtest <file> ...     run a backtest (--enforce-live-breakers; --chaos for seeded stress)
            sweep <file> ...        grid-search params (--param fast=5,10,15 --rank sharpe)
            walkforward <file> ...  rolling in-sample/out-of-sample validation
            experiment run --plan <yaml>
                                    governed train/validation/test research run
            research <file> ...     interactive playback REPL over historical data
            run <file> ...          run a strategy in foreground (paper-trading)

        DAEMON LIFECYCLE
            daemon start            start the long-lived daemon process
            daemon stop             stop the running daemon
            daemon status           show daemon health

        DAEMON OPERATIONS
            deploy <file> --as <n>  register and start a strategy in the daemon
            resync <file> --as <n>  safely replace a running daemon deployment
            list                    list deployed strategies
            status [<name>]         show status of one strategy or all
            status --deep           aggregated health check (exit 1 if unhealthy)
            logs <name> [-f]        tail per-strategy log file
            stop <name> [--flatten] gracefully stop a deployed strategy
            start <portfolio>/<c>   clear operator-stop on a portfolio child

        ONE-SHOT TRADING (AI/manual overlay)
            bot buy|sell ...        place a one-shot order (see qkt bot help)
            bot close|modify|cancel manage venue positions and pending orders
            bot account|positions|orders|quote|bars|history|eval
                                    read venue truth and evaluate indicators

        VENUE / FEED
            brokers list            list configured broker profiles
            instruments verify      compare instruments.yaml with MT5 /symbol_info
            audit-ticks ...         capture and audit live MT5 ticks
            fetch BROKER:SYMBOL --tf <tf> --from <date> --to <date>
                                    backfill historical bars into the local store
            fetch BROKER:SYMBOL --tf <tf> --last 30d
                                    same, but for the last N days
            data verify <symbol>    check cached tick day files for empty/corrupt/gappy data
            preflight <file>        validate production readiness (--production to fail closed)
            promotion ...           record/query promotion states, approvals, waivers, gates
            incident collect        build an incident zip with journal/log/state evidence
            golden capture          export replayable MT5 trading or strict read-only evidence
            golden materialize      verify a golden ZIP and build normal tick/bar replay stores
            soak report             derive exact-image paper-soak promotion evidence

        EDITOR INTEGRATIONS
            editor list             show supported editors + what's detected on this machine
            editor install <t>      install for vscode, nvim, vim, or all
            editor uninstall <t>    remove a previously-installed integration
            editor grammar          print the generated grammar (--format textmate|vim)

        FLAGS
            --version, -v           print qkt version
            --help, help            this message

        DOCS
            https://elitekaycy.github.io/qkt/
        """.trimIndent()
}
