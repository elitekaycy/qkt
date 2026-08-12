package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal

/** Point-in-time durability health exposed to live-session controls and operator status. */
data class PersistenceHealth(
    val enabled: Boolean,
    val totalWrites: Long = 0L,
    val slowWrites: Long = 0L,
    val failedWrites: Long = 0L,
    val consecutiveFailures: Long = failedWrites,
    val failureEpisodes: Long = if (failedWrites == 0L) 0L else 1L,
    val queueSize: Int = 0,
    val callerRunsTotal: Long = 0L,
) {
    companion object {
        /** Health for in-memory/no-op persistence where no durable writes are expected. */
        val DISABLED = PersistenceHealth(enabled = false)
    }
}

/**
 * Durable storage for the in-memory engine state that doesn't survive restart:
 * leg metadata, bracket linkages, in-flight orders, STACK_AT tier-fired state,
 * and active DSL exit hooks.
 *
 * Production implementations ([FileStatePersistor]) write atomic JSON files under
 * `<stateRoot>/<strategyId>/`, where the root is the daemon state directory (honors
 * `QKT_STATE_DIR`). Tests use [NoopStatePersistor] (in-memory) by default.
 *
 * All methods are write-on-mutate: the call site invokes a `save*` method synchronously
 * after every mutation to the underlying state object. Reads happen once at boot via
 * [com.qkt.persistence.LegBookReconciler].
 */
interface StatePersistor : AutoCloseable {
    /** Releases persistence resources after all sessions have stopped. */
    override fun close() = Unit

    /** Current durability counters; disabled by default for non-durable test persistors. */
    fun healthSnapshot(): PersistenceHealth = PersistenceHealth.DISABLED

    fun saveLegBook(
        strategyId: String,
        symbol: String,
        legBook: LegBook,
    )

    fun loadLegBook(
        strategyId: String,
        symbol: String,
    ): PersistedLegBook?

    fun saveBracketPairs(
        strategyId: String,
        pairs: List<BracketPair>,
    )

    fun loadBracketPairs(strategyId: String): List<BracketPair>

    fun savePendingOrders(
        strategyId: String,
        orders: Map<String, OrderRequest>,
    )

    /**
     * Durably records venue-bound order intent before submission. Async decorators must
     * bypass their queue so acceptance cannot precede the recovery record.
     */
    fun savePendingOrdersSync(
        strategyId: String,
        orders: Map<String, OrderRequest>,
    ) = savePendingOrders(strategyId, orders)

    fun loadPendingOrders(strategyId: String): Map<String, OrderRequest>

    fun savePendingStacks(
        strategyId: String,
        perPrimary: Map<String, PersistedTierState>,
    )

    fun loadPendingStacks(strategyId: String): Map<String, PersistedTierState>

    /** Persist the live OCO legs for [strategyId] — identity + linkage for restart recovery. */
    fun saveOcoLegs(
        strategyId: String,
        legs: List<PersistedOcoLeg>,
    )

    /** Restore the live OCO legs for [strategyId]; empty when none persisted. */
    fun loadOcoLegs(strategyId: String): List<PersistedOcoLeg>

    /**
     * Persist engine-managed dynamic stops for [strategyId] — their static config plus runtime
     * cursor and high-water state — so restart resumes from the last durably recorded favorable
     * price. This does not reconstruct triggers or favorable extremes that occurred while offline.
     * Default no-op keeps persistors that predate this compiling.
     */
    fun saveTrailingStops(
        strategyId: String,
        stops: List<PersistedTrailingStop>,
    ) {}

    /** Restore engine-managed dynamic stops for [strategyId]; empty when none persisted. */
    fun loadTrailingStops(strategyId: String): List<PersistedTrailingStop> = emptyList()

    /**
     * Persist the session's complete risk snapshot: halt flags, realized PnL,
     * drawdown anchors, trailing peaks, and pacing state. Default no-op keeps
     * persistors that predate risk persistence compiling.
     */
    fun saveRiskState(
        strategyId: String,
        state: PersistedRiskState,
    ) {}

    /**
     * The last persisted risk snapshot, or null when none exists.
     *
     * Implementations must surface unreadable or incompatible persisted state. Treating those
     * cases as absent would let a restart discard a halt and reset the session's daily-loss budget.
     */
    fun loadRiskState(strategyId: String): PersistedRiskState? = null

    /**
     * Persist a strategy's lifetime realized PnL. Without this, every restart
     * resets realized to zero and equity snaps back to the starting balance —
     * downstream consumers (dashboards, drawdown stats) see a cliff that never
     * happened. Default no-op keeps persistors that predate PnL persistence compiling.
     */
    fun savePnl(
        strategyId: String,
        state: PersistedPnl,
    ) {}

    /** The last persisted lifetime PnL, or null when none exists. */
    fun loadPnl(strategyId: String): PersistedPnl? = null

    /** Persist the bounded closed-trade outcome buffer for streak and cooldown accessors. */
    fun saveTradeHistory(
        strategyId: String,
        state: PersistedTradeHistory,
    ) {}

    /** The last persisted closed-trade outcome buffer, or null when none exists. */
    fun loadTradeHistory(strategyId: String): PersistedTradeHistory? = null

    /** Persist DSL `SEQUENCE` progress for deterministic restart recovery. */
    fun saveSequences(
        strategyId: String,
        states: Map<String, PersistedSequenceState>,
    ) {}

    /** Restore DSL `SEQUENCE` progress for [strategyId]; empty when none persisted. */
    fun loadSequences(strategyId: String): Map<String, PersistedSequenceState> = emptyMap()

    /** Persist active one-shot DSL exit-hook bindings for restart recovery. */
    fun saveExitHooks(
        strategyId: String,
        bindings: List<PersistedExitHookBinding>,
    ) {}

    /** Restore active DSL exit-hook bindings for [strategyId]. */
    fun loadExitHooks(strategyId: String): List<PersistedExitHookBinding> = emptyList()

    fun clearStrategy(strategyId: String)
}

/**
 * On-disk shape of a strategy's lifetime PnL: the cumulative realized amount since
 * the strategy first deployed (not the daily figure — that lives in [PersistedRiskState]).
 */
data class PersistedPnl(
    val realized: BigDecimal,
)

/** On-disk shape of one non-zero closed-trade outcome used by streak accessors. */
data class PersistedTradeOutcome(
    val timestamp: Long,
    val pnl: BigDecimal,
    val symbol: String,
)

/** On-disk shape of the bounded closed-trade outcome buffer for one strategy. */
data class PersistedTradeHistory(
    val outcomes: List<PersistedTradeOutcome>,
)

/** Persisted snapshot captured when a DSL `SEQUENCE` stage completed. */
data class PersistedSequenceSnapshot(
    val stage: String,
    val price: BigDecimal,
    val timeMs: Long,
)

/**
 * Persisted runtime state for one DSL `SEQUENCE`.
 *
 * [lastValues] preserves stage edge detection across restart. [completePulse]
 * preserves the one-pass completion window until rules consume it.
 */
data class PersistedSequenceState(
    val name: String,
    val stage: Int,
    val snapshots: List<PersistedSequenceSnapshot>,
    val lastValues: Map<String, Boolean> = emptyMap(),
    val completePulse: Boolean = false,
)

/** Durable lifecycle state for one hook-bearing parent order. */
data class PersistedExitHookBinding(
    val bindingId: String,
    val strategyId: String,
    val symbol: String,
    val entrySide: Side,
    val definitionId: String,
    val fingerprint: String,
    val entryOrderIds: List<String>,
    val stopOrderIds: List<String>,
    val takeProfitOrderIds: List<String>,
    val closeOrderIds: List<String> = emptyList(),
    val brokerTickets: List<String> = emptyList(),
    val activeQuantity: BigDecimal,
    val exitQuantity: BigDecimal,
    val exitPnl: BigDecimal,
)

data class PersistedLeg(
    val legId: String,
    val parentLegId: String?,
    val role: LegRole,
    val side: Side,
    val symbol: String,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val openedAt: Long,
    val brokerTicket: String? = null,
) {
    fun toPositionLeg(): PositionLeg =
        PositionLeg(
            legId = legId,
            parentLegId = parentLegId,
            role = role,
            side = side,
            symbol = symbol,
            quantity = quantity,
            entryPrice = entryPrice,
            openedAt = openedAt,
            brokerTicket = brokerTicket,
        )

    companion object {
        fun fromPositionLeg(leg: PositionLeg): PersistedLeg =
            PersistedLeg(
                legId = leg.legId,
                parentLegId = leg.parentLegId,
                role = leg.role,
                side = leg.side,
                symbol = leg.symbol,
                quantity = leg.quantity,
                entryPrice = leg.entryPrice,
                openedAt = leg.openedAt,
                brokerTicket = leg.brokerTicket,
            )
    }
}

data class PersistedLegBook(
    val strategyId: String,
    val symbol: String,
    val legs: List<PersistedLeg>,
)

data class BracketPair(
    val entryClientOrderId: String,
    val stopLossClientOrderId: String?,
    val takeProfitClientOrderId: String?,
    val legId: String?,
)

data class PersistedTier(
    val index: Int,
    val mfeThreshold: BigDecimal,
    val withinMs: Long,
    val stackQuantity: BigDecimal,
    val slDistance: BigDecimal,
    val tpDistance: BigDecimal,
    val maeRecoverDistance: BigDecimal? = null,
    val armedAdverseExtreme: BigDecimal? = null,
    val fired: Boolean,
    val firedAt: Long?,
    val firedLegId: String?,
    /** True when this tier's MFE window elapsed unfired — it must not fire after a restart. */
    val abandoned: Boolean = false,
)

data class PersistedTierState(
    val primaryClientOrderId: String,
    val tiers: List<PersistedTier>,
    /**
     * When the parent leg opened (the engine's MFE-window anchor). Restored engines
     * keep counting their `WITHIN` windows from the ORIGINAL open, not the restart.
     * Null in pre-restore state files; restore falls back to "now" with a warning.
     */
    val openedAtMs: Long? = null,
)

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
    val globalRealizedTotal: java.math.BigDecimal? = null,
    val dailyDrawdownEpochDay: Long? = null,
    val globalDailyDrawdownRef: java.math.BigDecimal? = null,
    val perStrategyDailyDrawdownRefs: Map<String, java.math.BigDecimal> = emptyMap(),
    val peakTotalEquity: java.math.BigDecimal? = null,
    val perStrategyPeakEquity: Map<String, java.math.BigDecimal> = emptyMap(),
    val pacerEntryFillsByStrategy: Map<String, List<Long>> = emptyMap(),
    val pacerLossStreakByStrategy: Map<String, Int> = emptyMap(),
    val pacerLastLossAtByStrategy: Map<String, Long> = emptyMap(),
)

/** One strategy-scoped halt inside [PersistedRiskState]. */
data class PersistedStrategyHalt(
    val strategyId: String,
    val reason: String,
    val scope: String,
    val epochDay: Long,
)
