package com.qkt.backtest

import java.math.BigDecimal

/** One book-risk reading over time: exposure + equity at a sample instant. */
data class BookRiskSample(
    val timestampMs: Long,
    val grossExposure: BigDecimal,
    val netExposure: BigDecimal,
    val bookEquity: BigDecimal,
)

/** An auditable book-risk action. Empty until the limit/de-risk/allocation phases populate it. */
sealed interface BookRiskEvent {
    val timestampMs: Long

    data class LimitBreach(
        override val timestampMs: Long,
        val kind: String,
        val detail: String,
    ) : BookRiskEvent

    data class DeRiskChange(
        override val timestampMs: Long,
        val factor: BigDecimal,
    ) : BookRiskEvent

    data class Rebalance(
        override val timestampMs: Long,
        val weights: Map<String, BigDecimal>,
    ) : BookRiskEvent
}

/**
 * The book-risk dataset for a portfolio run: a decimated time series of exposure + equity, summary
 * stats, and the event log. Null on single-strategy runs (no book). This is the "exact data we need"
 * to see how the book behaved — surfaced in `--json` (summary) and the `--report` bundle (full csv).
 */
data class BookRiskReport(
    val series: List<BookRiskSample>,
    val bookVol: BigDecimal?,
    val maxGrossExposure: BigDecimal,
    val maxNetExposure: BigDecimal,
    val events: List<BookRiskEvent> = emptyList(),
)
