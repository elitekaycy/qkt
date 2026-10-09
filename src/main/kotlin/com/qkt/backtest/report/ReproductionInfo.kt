package com.qkt.backtest.report

/**
 * Everything needed to reproduce a backtest exactly, embedded in `report.html` so the file alone
 * is sufficient to re-run it: the full command line, the strategy and config sources, and the
 * build identity. Read by [HtmlReportWriter] only — never serialized into `result.json`, whose
 * schema stays frozen for tooling.
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
)
