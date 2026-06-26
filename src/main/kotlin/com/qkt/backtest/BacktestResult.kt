package com.qkt.backtest

import com.qkt.events.RiskRejectedEvent
import com.qkt.evidence.EvidenceEnvelope
import com.qkt.observability.LatencyRegistry
import com.qkt.positions.Position

data class BacktestResult(
    val trades: List<TradeRecord>,
    val rejections: List<RiskRejectedEvent>,
    val finalPositions: Map<String, Position>,
    val global: PerformanceReport,
    val perStrategy: Map<String, PerformanceReport>,
    val cadence: SampleCadence,
    /**
     * Pipeline latency observations (#150). Non-null only when the backtest was run
     * with `latencyEnabled = true`. Contains per-(strategyId, stage) percentile snapshots.
     */
    val latencyReport: LatencyRegistry.Report? = null,
    /**
     * Risk halts fired during the replay, in order. A non-empty list means the
     * configured halt rules stopped trading partway — bars after the last halt
     * (without a resume) produced no entries, exactly as live would behave.
     */
    val halts: List<com.qkt.events.RiskEvent.Halted> = emptyList(),
    /**
     * Lag-1 return autocorrelation by UTC hour and volatility regime, keyed per symbol
     * (#460). A market-structure statistic computed once over the bar stream, not per
     * strategy. Empty when the replay was tick-only (no candle window) or saw fewer than
     * the minimum bars to populate any bucket.
     */
    val conditionalAutocorr: Map<String, ConditionalAutocorr> = emptyMap(),
    /**
     * Cross-strategy book analytics (return correlation, contribution to return / risk / drawdown).
     * Null on single-strategy runs, where the per-strategy report already says everything.
     */
    val bookAnalytics: BookAnalytics? = null,
    /**
     * Book-risk measurement series + events (exposure, vol, limits, de-risk, rebalances). Null on
     * single-strategy runs. The dataset for seeing how the book behaved.
     */
    val bookRisk: BookRiskReport? = null,
    /**
     * Institutional-readiness provenance and assumptions. Null on internal/unit-test results or older
     * call sites; CLI commands attach it before printing/report writing.
     */
    val evidence: EvidenceEnvelope? = null,
    /**
     * Account-currency conversion and cost-model disclosure for this run. Present for replay/backtest
     * results that use the shared engine; older/unit-test results may leave it null.
     */
    val accounting: com.qkt.accounting.AccountingSnapshot? = null,
)
