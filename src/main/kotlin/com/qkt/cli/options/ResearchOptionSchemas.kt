package com.qkt.cli.options

import com.qkt.cli.CliOptionSchema

private val backtestValues =
    setOf(
        "from",
        "to",
        "starting-balance",
        "symbols",
        "data-root",
        "hub-root",
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
        "stop-latency",
        "tp-fill",
        "slippage",
        "reject-every",
        "partial-fill",
        "dataset",
        "position-mode",
    )
private val backtestFlags =
    setOf("no-fetch", "bars", "allow-incomplete", "tick-fills", "enforce-live-breakers", "chaos")

/** Option schemas for the backtest-family research commands, which share the backtest data and execution flags. */
internal val researchOptionSchemas: Map<String, CliOptionSchema> =
    mapOf(
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
                            "report-dir",
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
    )
