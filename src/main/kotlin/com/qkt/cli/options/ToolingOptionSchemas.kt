package com.qkt.cli.options

import com.qkt.cli.CliOptionSchema

/** Option schemas for parsing, editor and scaffolding tools, market data and broker metadata, the bot CLI, and help. */
internal val toolingOptionSchemas: Map<String, CliOptionSchema> =
    mapOf(
        "parse" to CliOptionSchema(),
        "lsp" to CliOptionSchema(),
        "brokers" to CliOptionSchema(values = setOf("config"), flags = setOf("json")),
        "instruments" to
            CliOptionSchema(
                values = setOf("config", "instruments", "broker", "symbols", "as-prefix", "out"),
                flags = setOf("json"),
            ),
        "editor" to
            CliOptionSchema(
                flags = setOf("yes", "y"),
                shortAliases = mapOf("-y" to "--yes"),
            ),
        "create" to CliOptionSchema(values = setOf("kind")),
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
                        "run",
                        "symbols",
                        "from",
                        "to",
                        "identities",
                        "out",
                        "history-bars",
                        "starting-balance",
                        "data-root",
                        "instruments",
                        "fetcher",
                        "fetcher-script",
                    ),
                flags = setOf("json", "dry-run", "all", "backtest", "no-fetch", "tick-fills", "bars"),
            ),
        "--version" to CliOptionSchema(),
        "-v" to CliOptionSchema(),
        "--help" to CliOptionSchema(),
        "help" to CliOptionSchema(),
    )
