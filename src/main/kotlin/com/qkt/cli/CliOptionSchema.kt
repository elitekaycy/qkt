package com.qkt.cli

internal data class CliOptionSchema(
    val values: Set<String> = emptySet(),
    val flags: Set<String> = emptySet(),
    val optionalValues: Set<String> = emptySet(),
    val shortAliases: Map<String, String> = emptyMap(),
)

internal object CliOptionSchemas {
    private val backtestValues =
        setOf(
            "from",
            "to",
            "starting-balance",
            "symbols",
            "data-root",
            "fetcher",
            "fetcher-script",
            "instruments",
            "broker",
            "config",
            "bar-tf",
            "fx-symbol",
            "account-currency",
            "fx-missing-policy",
            "fx-source",
            "seed",
            "execution",
            "execution-latency",
            "slippage",
            "reject-every",
            "partial-fill",
            "dataset",
        )
    private val backtestFlags =
        setOf("no-fetch", "bars", "allow-incomplete", "tick-fills", "enforce-live-breakers", "chaos")

    private val schemas: Map<String, CliOptionSchema> =
        mapOf(
            "parse" to CliOptionSchema(),
            "lsp" to CliOptionSchema(),
            "backtest" to
                CliOptionSchema(
                    values = backtestValues + setOf("param", "report-dir"),
                    flags = backtestFlags + setOf("json", "debug"),
                ),
            "sweep" to
                CliOptionSchema(
                    values =
                        backtestValues +
                            setOf("param", "rank", "parallelism", "scenarios", "large-search-threshold"),
                    flags = backtestFlags + setOf("json"),
                ),
            "walkforward" to
                CliOptionSchema(
                    values =
                        backtestValues +
                            setOf(
                                "param",
                                "rank",
                                "parallelism",
                                "train",
                                "test",
                                "step",
                                "topN",
                                "large-search-threshold",
                            ),
                    flags = backtestFlags + setOf("json"),
                ),
            "experiment" to
                CliOptionSchema(
                    values =
                        setOf(
                            "plan",
                            "strategy",
                            "parallelism",
                            "dataset",
                            "registry-dir",
                            "out-dir",
                            "data-root",
                            "config",
                            "broker",
                            "instruments",
                            "starting-balance",
                        ),
                    flags = setOf("json", "bars", "no-fetch", "allow-incomplete"),
                ),
            "research" to CliOptionSchema(values = backtestValues, flags = backtestFlags),
            "run" to
                CliOptionSchema(
                    values =
                        setOf(
                            "source",
                            "port",
                            "bind",
                            "port-file",
                            "ring-size",
                            "shutdown-timeout",
                            "config",
                        ),
                    flags = setOf("allow-privileged-port", "no-observe", "flatten-on-stop"),
                ),
            "deploy" to deploySchema(),
            "resync" to deploySchema(flags = setOf("dry-run", "json")),
            "list" to CliOptionSchema(values = setOf("state-dir"), flags = setOf("json")),
            "stop" to
                CliOptionSchema(
                    values = setOf("timeout", "state-dir"),
                    flags = setOf("flatten", "json"),
                ),
            "start" to CliOptionSchema(values = setOf("state-dir")),
            "halt" to CliOptionSchema(values = setOf("state-dir"), flags = setOf("json")),
            "kill" to
                CliOptionSchema(
                    values = setOf("state-dir"),
                    flags = setOf("flatten", "json"),
                ),
            "reconcile" to CliOptionSchema(values = setOf("state-dir"), flags = setOf("json")),
            "resume" to CliOptionSchema(values = setOf("state-dir"), flags = setOf("json")),
            "brokers" to CliOptionSchema(values = setOf("config"), flags = setOf("json")),
            "instruments" to
                CliOptionSchema(
                    values = setOf("config", "instruments", "broker"),
                    flags = setOf("json"),
                ),
            "editor" to
                CliOptionSchema(
                    flags = setOf("yes", "y"),
                    shortAliases = mapOf("-y" to "--yes"),
                ),
            "create" to CliOptionSchema(values = setOf("kind")),
            "audit-ticks" to
                CliOptionSchema(
                    values =
                        setOf(
                            "symbol",
                            "duration",
                            "mt5-profile",
                            "poll-ms",
                            "reference",
                            "settle-ms",
                            "config",
                            "mt5-symbol",
                            "out",
                        ),
                    flags = setOf("json"),
                ),
            "fetch" to
                CliOptionSchema(
                    values = setOf("tf", "from", "to", "last", "data-root", "config"),
                ),
            "data" to
                CliOptionSchema(
                    values =
                        setOf(
                            "snapshot",
                            "data-root",
                            "from",
                            "to",
                            "out",
                            "vendor",
                            "quality",
                            "max-gap-minutes",
                            "tf",
                        ),
                    flags =
                        setOf(
                            "strict",
                            "allow-empty-days",
                            "require-bid-ask",
                            "require-volume",
                            "allow-corrupt-days",
                            "prune",
                        ),
                ),
            "preflight" to
                CliOptionSchema(
                    values = setOf("config", "state-dir"),
                    flags = setOf("production", "offline"),
                ),
            "promotion" to
                CliOptionSchema(
                    values =
                        setOf(
                            "as",
                            "state",
                            "reason",
                            "actor",
                            "gate",
                            "expires",
                            "state-dir",
                            "strategy",
                            "config",
                            "registry-dir",
                            "evidence",
                            "paper-status",
                            "paper-days",
                            "paper-trades",
                            "avg-slippage-bps",
                            "p95-slippage-bps",
                            "rejection-rate-pct",
                            "missed-fills",
                        ),
                    flags = setOf("json", "all"),
                ),
            "incident" to
                CliOptionSchema(
                    values =
                        setOf(
                            "out",
                            "strategy",
                            "since",
                            "until",
                            "max-file-bytes",
                            "config",
                            "strategy-file",
                            "state-dir",
                        ),
                ),
            "golden" to
                CliOptionSchema(
                    values = setOf("session", "state-dir", "bundle", "out"),
                    flags = setOf("read-only"),
                ),
            "soak" to
                CliOptionSchema(
                    values =
                        setOf(
                            "testing-sha",
                            "image",
                            "started-at",
                            "completed-at",
                            "trading-days",
                            "health",
                            "reconciliation",
                            "golden",
                            "out",
                        ),
                ),
            "bot" to
                CliOptionSchema(
                    values =
                        setOf(
                            "config",
                            "broker",
                            "tf",
                            "count",
                            "since",
                            "as",
                            "state-dir",
                            "stop-limit",
                            "expires",
                            "tif",
                            "sizing",
                            "limit",
                            "stop",
                            "sl",
                            "tp",
                            "ticket",
                            "partial",
                            "order",
                        ),
                    flags = setOf("json", "dry-run", "all"),
                ),
            "daemon" to
                CliOptionSchema(
                    values = setOf("state-dir", "config", "control-port", "load-dir"),
                    flags = setOf("json"),
                ),
            "logs" to
                CliOptionSchema(
                    values = setOf("lines", "since", "state-dir"),
                    flags = setOf("follow"),
                    shortAliases = mapOf("-f" to "--follow"),
                ),
            "status" to
                CliOptionSchema(
                    values = setOf("state-dir"),
                    flags = setOf("latency", "deep"),
                ),
            "observe" to
                CliOptionSchema(
                    values = setOf("strategy", "since", "windows", "state-dir", "control-port"),
                ),
            "--version" to CliOptionSchema(),
            "-v" to CliOptionSchema(),
            "--help" to CliOptionSchema(),
            "help" to CliOptionSchema(),
        )

    fun forSubcommand(subcommand: String): CliOptionSchema? = schemas[subcommand]

    private fun deploySchema(flags: Set<String> = setOf("json")): CliOptionSchema =
        CliOptionSchema(
            values = setOf("as", "state-dir", "reconcile", "reason"),
            flags = flags,
            optionalValues = setOf("waive"),
        )
}
