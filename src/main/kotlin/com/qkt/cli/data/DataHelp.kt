package com.qkt.cli.data

/**
 * `qkt data <action> --help`: each action's usage, what it does and its flags. `qkt data --help` lists the
 * actions. Every action takes `--data-root <dir>` and `--config <file>` (see [com.qkt.cli.CliDataRoot]).
 */
internal object DataHelp {
    private class Action(
        val usage: String,
        val what: String,
        val flags: List<Pair<String, String>>,
    )

    private const val DOCS = "https://docs.tryqkt.com/how-to/import-data/"

    private val actions =
        linkedMapOf(
            "import" to
                Action(
                    "qkt data import <file|dir>... --as <SYMBOL> [--dry-run] [--plan] [--json]",
                    "Import ticks, bars, funding, open interest or a published series from a file you have. " +
                        "A built-in " +
                        "preset is matched without flags (qkt data presets); --dry-run reads and checks without writing.",
                    listOf(
                        "--as SYMBOL" to "where it lands: SYMBOL for ticks, SOURCE:SYMBOL for bars (EXNESS:XAUUSD)",
                        "--dry-run" to "read, check and report; write nothing",
                        "--kind KIND" to "ticks, bars, funding, open-interest or series, when no preset fits",
                        "--preset ID|FILE" to "a built-in preset id, or a YAML file saved with --plan",
                        "--map ROLE=EXPR" to "which column feeds which field: time=Date+Time, close=Close, bid=\$1",
                        "--time-format FMT" to "epoch, epoch:s|ms|us|ns, iso, or a java.time pattern",
                        "--tz CLOCK" to "the clock of times without a zone: UTC, +02:00, ny-close or an IANA zone",
                        "--trust-clock" to "accept a stated clock that the file's weekly opens contradict",
                        "--tf TF" to "the bar timeframe, when the file does not show it",
                        "--price bid|ask|mid|last" to "which price the bars quote",
                        "--label open|close" to "whether a bar's time is its open or its close",
                        "--digits N" to "the instrument's price digits (spreads in points, float32 sources)",
                        "--on-overlap MODE" to "fill (default: write days the store lacks), skip, replace or refuse",
                        "--sort" to "sort rows that are out of order (ties keep file order)",
                        "--on-conflict refuse|last" to
                            "funding/open interest: two values at one time refuse, or keep the later file's",
                        "--where COL=VALUE" to "keep only matching rows",
                        "--available EXPR, --available-lag DUR" to "series: when each value became known",
                        "--decimal C, --delimiter C, --encoding E, --format json --rows PATH" to "read unusual files",
                        "--plan" to "print the resolved description as a preset YAML instead of importing",
                        "--json" to "the report as JSON, for tools",
                    ),
                ),
            "verify" to
                Action(
                    "qkt data verify <SYMBOL|SOURCE:SYMBOL> [--tf TF] | --snapshot <file> [--strict]",
                    "Check a stored series day by day: ticks for SYMBOL, bars for SOURCE:SYMBOL. Flags unreadable, " +
                        "misplaced or out-of-order days and, by the rule backtests apply, trading days that are " +
                        "empty, have over 90 minutes of session time without data, or have no file; shows " +
                        "holidays and holiday short sessions. Exit 1 when flagged.",
                    listOf(
                        "--tf TF" to "SOURCE:SYMBOL only: check one timeframe folder",
                        "--snapshot FILE" to "check that a snapshot's days are unchanged (qkt data snapshot)",
                        "--strict" to "with --snapshot: apply the strict quality policy",
                    ),
                ),
            "bars" to
                Action(
                    "qkt data bars <SYMBOL|SOURCE:SYMBOL> --tf TF --from DATE --to DATE [--bars]",
                    "Print a series' bars as CSV exactly as a backtest reads them: stored bars, " +
                        "bars rolled up from a " +
                        "finer stored timeframe, or bars built from ticks. --to is inclusive.",
                    listOf("--bars" to "read the built .bin tier, as qkt backtest --bars does"),
                ),
            "compare" to
                Action(
                    "qkt data compare <SYMBOL> --against <SYMBOL> --from DATE --to DATE [--tf 1m] [--json]",
                    "Compare two stored series bar by bar: matched and missing bars, the clock offset that best " +
                        "aligns them, price differences in points. Exit 1 when they differ.",
                    listOf(
                        "--tf TF" to "the timeframe compared (default 1m)",
                        "--instruments FILE" to "an instruments.yaml giving the point size",
                        "--json" to "the result as JSON",
                    ),
                ),
            "build-bars" to
                Action(
                    "qkt data build-bars <SYMBOL> --tf TF [--from DATE] [--to DATE]",
                    "Build bars from a symbol's stored ticks into bars/BACKTEST/<SYMBOL>/<tf>/<day>.bin, which " +
                        "qkt backtest --bars reads. A day whose ticks changed is rebuilt. --to is exclusive.",
                    emptyList(),
                ),
            "convert" to
                Action(
                    "qkt data convert <SYMBOL> [--from DATE] [--to DATE] [--prune]",
                    "Write a compact .bin beside each stored tick day; qkt reads .bin first, so backtests decode faster.",
                    listOf("--prune" to "delete each day's CSV once its .bin reads back as the same ticks"),
                ),
            "snapshot" to
                Action(
                    "qkt data snapshot <SYMBOL> --from DATE --to DATE --out <file> [--vendor V] [--quality Q]",
                    "Pin a dataset: every day file's hash, the days a closed market has no file, the range and the " +
                        "vendor. qkt backtest --dataset <file> then refuses to run on changed data.",
                    listOf(
                        "--vendor V" to "a label for where the data came from",
                        "--quality research|strict" to "the policy qkt data verify --snapshot applies",
                        "--max-gap-minutes N" to
                            "the largest gap within a day the policy accepts (default 90, past a daily break)",
                        "--allow-missing-days" to "pin weekdays with no file (holidays) as absent instead of refusing",
                        "--allow-empty-days, --require-bid-ask, --require-volume, --allow-corrupt-days" to "policy",
                    ),
                ),
            "sample" to
                Action(
                    "qkt data sample",
                    "Download the quick start's practice data (one month of Binance BTCUSDT one-minute bars, " +
                        "September 2026) from Binance, check its pinned SHA-256 and import it as BINANCE:BTCUSDT.",
                    emptyList(),
                ),
            "presets" to
                Action(
                    "qkt data presets [--json]",
                    "List the built-in import presets: every source qkt data import recognises without flags.",
                    listOf("--json" to "as JSON"),
                ),
        )

    /** Help for [action], or the list of actions when it is null or unknown. */
    fun of(action: String?): String {
        val a = actions[action] ?: return overview()
        return buildString {
            appendLine()
            appendLine("Usage: ${a.usage}")
            appendLine()
            appendLine(wrap(a.what, "  "))
            appendLine()
            val width = (a.flags.map { it.first.length } + listOf("--data-root DIR".length)).max().coerceAtMost(40)
            for ((flag, text) in a.flags + common) appendLine("  ${flag.padEnd(width)}  $text")
            appendLine()
            appendLine("  Guide: $DOCS")
        }
    }

    private val summaries =
        linkedMapOf(
            "import" to "bring in a file you have (MT5, Dukascopy, HistData, Binance, Yahoo, any CSV/JSON)",
            "verify" to "check a stored series day by day",
            "bars" to "print a series' bars exactly as a backtest reads them",
            "compare" to "compare two stored series bar by bar",
            "build-bars" to "build bars from stored ticks for qkt backtest --bars",
            "convert" to "write compact .bin tick days that backtests decode faster",
            "snapshot" to "pin a dataset so a backtest refuses changed data",
            "presets" to "list the sources qkt data import recognises",
            "sample" to "download and import the quick start's practice data",
        )

    private val common =
        listOf(
            "--data-root DIR" to "the store to use (else QKT_DATA_HOME, data_root in qkt.config.yaml, ~/.qkt/data)",
            "--config FILE" to "the qkt.config.yaml whose data_root to use",
        )

    private fun overview(): String =
        buildString {
            appendLine()
            appendLine("Usage: qkt data <action> [...]   (qkt data <action> --help for its flags)")
            appendLine()
            for ((name, line) in summaries) appendLine("  ${name.padEnd(11)} $line")
            appendLine()
            appendLine("  Guide: $DOCS")
        }

    private fun wrap(
        text: String,
        indent: String,
        width: Int = 100,
    ): String {
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(' ')) {
            if (line.length + word.length + 1 > width - indent.length && line.isNotEmpty()) {
                lines += indent + line.toString()
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) lines += indent + line.toString()
        return lines.joinToString("\n")
    }
}
