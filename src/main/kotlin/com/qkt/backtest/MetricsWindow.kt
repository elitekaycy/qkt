package com.qkt.backtest

import java.math.BigDecimal

/**
 * A named sub-window of one run, `[fromMs, toMs)`, whose metrics are computed from the same
 * full-resolution equity samples and closing fills as the run's `global` report (#1276) —
 * e.g. an out-of-sample tail. A window covering the whole run reproduces `global`.
 */
data class MetricsWindow(
    val name: String,
    val fromMs: Long,
    val toMs: Long,
) {
    init {
        require(name.isNotBlank()) { "metrics window name must be non-blank" }
        require(fromMs < toMs) { "metrics window '$name' must end after it starts" }
    }

    fun contains(timestampMs: Long): Boolean = timestampMs >= fromMs && timestampMs < toMs
}

/** The online metrics of one [MetricsWindow], fed only the samples inside it. */
class WindowSamples(
    val window: MetricsWindow,
) {
    val metrics = EquityMetrics()

    /** Equity of the last in-window sample, or null before the first. */
    var lastEquity: BigDecimal? = null
        private set

    fun accept(
        timestamp: Long,
        equity: BigDecimal,
    ) {
        if (!window.contains(timestamp)) return
        metrics.accept(timestamp, equity)
        lastEquity = equity
    }
}

/**
 * One window's finished report. [report] carries the same fields as the run's `global`
 * ([PerformanceReport]); its `totalPnL` is the equity change between the window's first and last
 * samples ([equityStart] to [equityEnd]) and its trade statistics count the closing fills stamped
 * inside the window.
 */
data class WindowReport(
    val window: MetricsWindow,
    val samples: Int,
    val closingFills: Int,
    val equityStart: BigDecimal,
    val equityEnd: BigDecimal,
    val report: PerformanceReport,
)
