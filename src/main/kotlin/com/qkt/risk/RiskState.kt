package com.qkt.risk

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SystemClock
import com.qkt.events.RiskEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.RestorablePnLProvider
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Aggregate mutable state the risk subsystem reads on every order and updates on
 * every fill / equity refresh. Owns the [EquityTracker], [DrawdownTracker], and
 * [DailyPnLTracker] for both the global account and each strategy. Exposes
 * read-only views via [RiskViewImpl].
 *
 * Halts are first-class: a strategy halt suppresses that strategy's submissions
 * without affecting others; the global halt suppresses everything. Halts persist
 * until [clear]/[clearStrategy] is called by an operator — except
 * [HaltScope.TRANSIENT] halts, which are never written to the snapshot so a
 * restart or resync replacement starts unhalted.
 */
class RiskState(
    private val pnl: PnLProvider,
    strategyPnL: StrategyPnL,
    private val clock: Clock,
    private val bus: EventBus,
    initialBalance: BigDecimal = BigDecimal.ZERO,
    dailyDdBasis: DailyDrawdownBasis = DailyDrawdownBasis.BALANCE,
    /**
     * Persistence sink for the complete restart-safe risk snapshot — invoked after state changes
     * so a restart restores them ([com.qkt.persistence.StatePersistor.saveRiskState]).
     * Null disables (backtests, tests).
     */
    private val persist: ((com.qkt.persistence.PersistedRiskState) -> Unit)? = null,
    val pacerLedger: PacerLedger = PacerLedger(),
) {
    private val anchorsDirty = AtomicBoolean(false)
    val equityTracker: EquityTracker = EquityTracker(pnl, strategyPnL, initialBalance)
    val drawdownTracker: DrawdownTracker = DrawdownTracker(equityTracker)
    val dailyPnLTracker: DailyPnLTracker = DailyPnLTracker(clock)
    val dailyDrawdownTracker: DailyDrawdownTracker =
        DailyDrawdownTracker(
            clock,
            dailyDdBasis,
            initialBalance,
            pnl,
            strategyPnL,
            // onTick/onFill refresh the equity tracker before halt rules evaluate, so these
            // cached reads carry the same value the tracker would recompute — minus a second
            // full unrealized-PnL walk per tick.
            currentGlobalEquity = equityTracker::currentEquity,
            currentStrategyEquity = equityTracker::currentEquityFor,
            onAnchorChanged = { anchorsDirty.set(true) },
        )

    @Volatile
    var halted: Boolean = false
        private set

    @Volatile
    var haltReason: String? = null
        private set

    @Volatile
    private var haltScope: HaltScope = HaltScope.PERSISTENT

    @Volatile
    private var haltEpochDay: Long = 0L

    @Volatile
    var warmupComplete: Boolean = false

    /** Per-strategy halt record: reason + scope + the UTC day it tripped (for daily auto-resume). */
    private data class HaltInfo(
        val reason: String,
        val scope: HaltScope,
        val epochDay: Long,
    )

    private val haltedStrategies: MutableMap<String, HaltInfo> = ConcurrentHashMap()

    fun isStrategyHalted(strategyId: String): Boolean = halted || strategyId in haltedStrategies

    fun haltReasonFor(strategyId: String): String? = if (halted) haltReason else haltedStrategies[strategyId]?.reason

    fun onTick() {
        if (equityTracker.update()) anchorsDirty.set(true)
        if (equityTracker.updateStrategies()) anchorsDirty.set(true)
    }

    /** Capture day-start references before the fill mutates realized PnL. */
    fun beforeFill(strategyId: String) {
        dailyDrawdownTracker.initializeStrategy(strategyId)
    }

    fun onFill(
        strategyId: String,
        realized: BigDecimal,
    ) {
        if (equityTracker.update()) anchorsDirty.set(true)
        if (equityTracker.updateStrategy(strategyId)) anchorsDirty.set(true)
        dailyPnLTracker.recordRealized(strategyId, realized)
        persistNow()
    }

    /**
     * Latch a global risk halt. [cancelWorkingOrders] false creates an entry-only halt: new
     * exposure is rejected while already-working protective exits remain live.
     */
    @Synchronized
    fun halt(
        reason: String,
        scope: HaltScope = HaltScope.PERSISTENT,
        cancelWorkingOrders: Boolean = true,
    ) {
        if (halted) {
            val escalates =
                (haltScope == HaltScope.DAILY && scope == HaltScope.PERSISTENT) ||
                    (haltScope == HaltScope.TRANSIENT && scope != HaltScope.TRANSIENT)
            if (escalates) {
                haltReason = reason
                haltScope = scope
                haltEpochDay = epochDay()
                bus.publish(
                    RiskEvent.Halted(
                        reason = reason,
                        strategyId = null,
                        cancelWorkingOrders = cancelWorkingOrders,
                        timestamp = clock.now(),
                    ),
                )
                persistNow()
            }
            return
        }
        halted = true
        haltReason = reason
        haltScope = scope
        haltEpochDay = epochDay()
        bus.publish(
            RiskEvent.Halted(
                reason = reason,
                strategyId = null,
                cancelWorkingOrders = cancelWorkingOrders,
                timestamp = clock.now(),
            ),
        )
        persistNow()
    }

    /** Scope of the active global halt, or null when global risk is not halted. */
    fun globalHaltScope(): HaltScope? = haltScope.takeIf { halted }

    fun haltStrategy(
        strategyId: String,
        reason: String,
        scope: HaltScope = HaltScope.PERSISTENT,
    ) {
        if (haltedStrategies.putIfAbsent(strategyId, HaltInfo(reason, scope, epochDay())) != null) return
        bus.publish(RiskEvent.Halted(reason = reason, strategyId = strategyId, timestamp = clock.now()))
        persistNow()
    }

    /**
     * Auto-resume DAILY-scoped halts once the UTC day they tripped on has passed — daily limits
     * reset each day. PERSISTENT halts (total/trailing drawdown) are left for an operator. Called by
     * [RiskEngine] before each halt-rule evaluation.
     */
    fun clearExpiredDailyHalts() {
        if (!halted && haltedStrategies.isEmpty()) return
        val today = epochDay()
        if (halted && haltScope == HaltScope.DAILY && haltEpochDay < today) resume()
        if (haltedStrategies.isEmpty()) return
        for ((id, info) in haltedStrategies) {
            if (info.scope == HaltScope.DAILY && info.epochDay < today) resumeStrategy(id)
        }
    }

    // UTC epoch-day without the per-call Instant/ZonedDateTime/LocalDate chain — this runs on
    // every tick via RiskEngine.evaluateHaltRules. floorDiv matches toEpochDay for all epoch
    // millis, including pre-1970.
    private fun epochDay(): Long = Math.floorDiv(clock.now(), MILLIS_PER_DAY)

    @Synchronized
    fun resume() {
        if (!halted) return
        halted = false
        haltReason = null
        bus.publish(RiskEvent.Resumed(strategyId = null, timestamp = clock.now()))
        persistNow()
    }

    /** True when [strategyId] carries a strategy-scoped halt (global halt not included). */
    fun strategyHalted(strategyId: String): Boolean = haltedStrategies.containsKey(strategyId)

    /** Snapshot of every strategy-scoped halt, for status/health surfaces (#1064). */
    fun strategyHalts(): List<com.qkt.persistence.PersistedStrategyHalt> =
        haltedStrategies.map { (id, info) ->
            com.qkt.persistence.PersistedStrategyHalt(id, info.reason, info.scope.name, info.epochDay)
        }

    fun resumeStrategy(strategyId: String) {
        if (haltedStrategies.remove(strategyId) == null) return
        bus.publish(RiskEvent.Resumed(strategyId = strategyId, timestamp = clock.now()))
        persistNow()
    }

    @Synchronized
    private fun persistNow() {
        anchorsDirty.set(false)
        persist?.invoke(snapshot())
    }

    /** Capture any missing day-start anchors and persist the complete restart state once. */
    fun initializeAnchors(strategyIds: Collection<String>) {
        dailyDrawdownTracker.initialize(strategyIds)
        if (equityTracker.update()) anchorsDirty.set(true)
        for (strategyId in strategyIds) {
            if (equityTracker.updateStrategy(strategyId)) anchorsDirty.set(true)
        }
        persistNow()
    }

    /** Flush peak/day-reference changes from a worker thread; a clean state performs no work. */
    @Synchronized
    fun persistAnchorsIfDirty() {
        if (!anchorsDirty.compareAndSet(true, false)) return
        persist?.invoke(snapshot())
    }

    /** Current realized PnL, drawdown anchors, pacing state, and halt flags. */
    fun snapshot(): com.qkt.persistence.PersistedRiskState {
        val daily = dailyPnLTracker.snapshot()
        val dailyDrawdown = dailyDrawdownTracker.snapshot()
        val equity = equityTracker.snapshot()
        val pacer = pacerLedger.snapshot(clock.now())
        return com.qkt.persistence.PersistedRiskState(
            epochDay = daily.epochDay,
            realizedToday = daily.global,
            perStrategyRealizedToday = daily.byStrategy,
            halted = halted && haltScope != HaltScope.TRANSIENT,
            haltReason = haltReason.takeUnless { haltScope == HaltScope.TRANSIENT },
            haltScope = haltScope.name,
            haltEpochDay = haltEpochDay,
            strategyHalts =
                haltedStrategies
                    .filterValues { it.scope != HaltScope.TRANSIENT }
                    .map { (id, info) ->
                        com.qkt.persistence.PersistedStrategyHalt(id, info.reason, info.scope.name, info.epochDay)
                    },
            globalRealizedTotal = pnl.realizedTotal(),
            dailyDrawdownEpochDay = dailyDrawdown.epochDay,
            globalDailyDrawdownRef = dailyDrawdown.globalRef,
            perStrategyDailyDrawdownRefs = dailyDrawdown.strategyRefs,
            peakTotalEquity = equity.globalPeak,
            perStrategyPeakEquity = equity.strategyPeaks,
            pacerEntryFillsByStrategy = pacer.entryFillsByStrategy,
            pacerLossStreakByStrategy = pacer.lossStreakByStrategy,
            pacerLastLossAtByStrategy = pacer.lastLossAtByStrategy,
        )
    }

    /**
     * Restore a persisted snapshot at boot. DAILY-scoped halts from a PAST UTC day are
     * NOT restored — they auto-resume at midnight (#354) and a restart must not revive
     * them; PERSISTENT halts and same-day DAILY halts come back exactly as they were.
     * Daily PnL and day-start drawdown references only carry over when their snapshot is
     * from today. Lifetime realized PnL, trailing peaks, and pacing streaks remain intact.
     * No events publish because these are pre-existing facts, not new state changes.
     */
    @Synchronized
    fun restore(persisted: com.qkt.persistence.PersistedRiskState) {
        persisted.globalRealizedTotal?.let { realized ->
            (pnl as? RestorablePnLProvider)?.restoreRealizedTotal(realized)
        }
        dailyPnLTracker.restore(
            DailyPnLSnapshot(persisted.epochDay, persisted.realizedToday, persisted.perStrategyRealizedToday),
        )
        persisted.dailyDrawdownEpochDay?.let { day ->
            dailyDrawdownTracker.restore(
                DailyDrawdownSnapshot(day, persisted.globalDailyDrawdownRef, persisted.perStrategyDailyDrawdownRefs),
            )
        }
        persisted.peakTotalEquity?.let { peak ->
            equityTracker.restore(EquityPeakSnapshot(peak, persisted.perStrategyPeakEquity))
        }
        pacerLedger.restore(
            PacerSnapshot(
                persisted.pacerEntryFillsByStrategy,
                persisted.pacerLossStreakByStrategy,
                persisted.pacerLastLossAtByStrategy,
            ),
        )
        anchorsDirty.set(false)
        val today = epochDay()
        val scope = runCatching { HaltScope.valueOf(persisted.haltScope) }.getOrDefault(HaltScope.PERSISTENT)
        val restorable =
            scope != HaltScope.TRANSIENT && (scope == HaltScope.PERSISTENT || persisted.haltEpochDay >= today)
        if (persisted.halted && restorable) {
            halted = true
            haltReason = persisted.haltReason
            haltScope = scope
            haltEpochDay = persisted.haltEpochDay
        }
        for (h in persisted.strategyHalts) {
            val hScope = runCatching { HaltScope.valueOf(h.scope) }.getOrDefault(HaltScope.PERSISTENT)
            if (hScope != HaltScope.TRANSIENT && (hScope == HaltScope.PERSISTENT || h.epochDay >= today)) {
                haltedStrategies[h.strategyId] = HaltInfo(h.reason, hScope, h.epochDay)
            }
        }
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L

        fun noOp(clock: Clock = SystemClock()): RiskState {
            val sequencer = MonotonicSequenceGenerator()
            val bus = EventBus(clock, sequencer)
            val prices = MarketPriceTracker()
            val positions = PositionTracker()
            val pnl = PnLCalculator(positions, prices)
            val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
            return RiskState(pnl, strategyPnL, clock, bus)
        }
    }
}
