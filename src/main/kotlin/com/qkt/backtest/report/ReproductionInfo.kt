package com.qkt.backtest.report

import com.qkt.evidence.ResolvedValue

/**
 * Everything needed to reproduce a backtest exactly, embedded in `report.html` so the file alone
 * is sufficient to re-run it: the full command line, the strategy and config sources, the merged
 * effective configuration with per-key provenance, and the build identity. Read by
 * [HtmlReportWriter] only — never serialized into `result.json`, whose schema stays frozen for
 * tooling (the machine-readable twin lives in evidence `resolved`).
 */
data class ReproductionInfo(
    /** Full command, e.g. `qkt backtest strategies/first.qkt --from 2025-09-01 ...`. */
    val commandLine: String,
    val strategyFile: String,
    val strategySource: String,
    val configFile: String?,
    val configSource: String?,
    val qktVersion: String,
    val gitSha: String,
    /** Effective configuration: key to merged value and the layer that supplied it. */
    val resolved: Map<String, ResolvedValue> = emptyMap(),
)
