package com.qkt.persistence

/**
 * On-disk shape of [com.qkt.risk.RiskState]: realized PnL, drawdown references,
 * trailing peaks, pacing state, and active halts.
 */
data class PersistedRiskState(
    val epochDay: Long,
    val realizedToday: java.math.BigDecimal,
    val perStrategyRealizedToday: Map<String, java.math.BigDecimal>,
    val halted: Boolean,
    val haltReason: String?,
    val haltScope: String,
    val haltEpochDay: Long,
    val strategyHalts: List<PersistedStrategyHalt>,
    /** When the global halt tripped, epoch ms; 0 when not halted or recorded before this was tracked. */
    val haltedAtMs: Long = 0L,
    val globalRealizedTotal: java.math.BigDecimal? = null,
    val dailyDrawdownEpochDay: Long? = null,
    val globalDailyDrawdownRef: java.math.BigDecimal? = null,
    val perStrategyDailyDrawdownRefs: Map<String, java.math.BigDecimal> = emptyMap(),
    val peakTotalEquity: java.math.BigDecimal? = null,
    val perStrategyPeakEquity: Map<String, java.math.BigDecimal> = emptyMap(),
    val pacerEntryFillsByStrategy: Map<String, List<Long>> = emptyMap(),
    val pacerLossStreakByStrategy: Map<String, Int> = emptyMap(),
    val pacerLastLossAtByStrategy: Map<String, Long> = emptyMap(),
    /** UTC month key (months since 1970-01) of [realizedMonth]; null in files written before #855. */
    val monthKey: Long? = null,
    val realizedMonth: java.math.BigDecimal? = null,
    val perStrategyRealizedMonth: Map<String, java.math.BigDecimal> = emptyMap(),
)
