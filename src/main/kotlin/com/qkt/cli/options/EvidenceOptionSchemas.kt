package com.qkt.cli.options

import com.qkt.cli.CliOptionSchema

/** Option schemas for the release-evidence commands: preflight, tick audits, promotion, incidents, golden captures and soaks. */
internal val evidenceOptionSchemas: Map<String, CliOptionSchema> =
    mapOf(
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
                        "coverage",
                        "parity",
                        "insights",
                        "out",
                    ),
            ),
    )
