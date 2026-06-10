package com.qkt.backtest

import com.qkt.events.RiskRejectedEvent
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
)
