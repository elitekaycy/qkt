package com.qkt.risk

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SystemClock
import com.qkt.events.RiskEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Aggregate mutable state the risk subsystem reads on every order and updates on
 * every fill / equity refresh. Owns the [EquityTracker], [DrawdownTracker], and
 * [DailyPnLTracker] for both the global account and each strategy. Exposes
 * read-only views via [RiskViewImpl].
 *
 * Halts are first-class: a strategy halt suppresses that strategy's submissions
 * without affecting others; the global halt suppresses everything. Halts persist
 * until [clear]/[clearStrategy] is called by an operator.
 */
class RiskState(
    pnl: PnLProvider,
    strategyPnL: StrategyPnL,
    private val clock: Clock,
    private val bus: EventBus,
    initialBalance: BigDecimal = BigDecimal.ZERO,
    dailyDdBasis: DailyDrawdownBasis = DailyDrawdownBasis.BALANCE,
    /**
     * Persistence sink for halt flags + daily PnL — invoked after every state change
     * so a restart restores them ([com.qkt.persistence.StatePersistor.saveRiskState]).
     * Null disables (backtests, tests).
     */
    private val persist: ((com.qkt.persistence.PersistedRiskState) -> Unit)? = null,
) {
    val equityTracker: EquityTracker = EquityTracker(pnl, strategyPnL, initialBalance)
    val drawdownTracker: DrawdownTracker = DrawdownTracker(equityTracker)
    val dailyPnLTracker: DailyPnLTracker = DailyPnLTracker(clock)
    val dailyDrawdownTracker: DailyDrawdownTracker =
        DailyDrawdownTracker(clock, dailyDdBasis, initialBalance, pnl, strategyPnL)

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
        equityTracker.update()
        equityTracker.updateStrategies()
    }

    fun onFill(
        strategyId: String,
        realized: BigDecimal,
    ) {
        equityTracker.update()
        equityTracker.updateStrategy(strategyId)
        dailyPnLTracker.recordRealized(strategyId, realized)
        persistNow()
    }

    @Synchronized
    fun halt(
        reason: String,
        scope: HaltScope = HaltScope.PERSISTENT,
    ) {
        if (halted) return
        halted = true
        haltReason = reason
        haltScope = scope
        haltEpochDay = epochDay()
        bus.publish(RiskEvent.Halted(reason = reason, strategyId = null, timestamp = clock.now()))
        persistNow()
    }

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
        val today = epochDay()
        if (halted && haltScope == HaltScope.DAILY && haltEpochDay < today) resume()
        for ((id, info) in haltedStrategies) {
            if (info.scope == HaltScope.DAILY && info.epochDay < today) resumeStrategy(id)
        }
    }

    private fun epochDay(): Long =
        java.time.Instant
            .ofEpochMilli(clock.now())
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .toEpochDay()

    @Synchronized
    fun resume() {
        if (!halted) return
        halted = false
        haltReason = null
        bus.publish(RiskEvent.Resumed(strategyId = null, timestamp = clock.now()))
        persistNow()
    }

    fun resumeStrategy(strategyId: String) {
        if (haltedStrategies.remove(strategyId) == null) return
        bus.publish(RiskEvent.Resumed(strategyId = strategyId, timestamp = clock.now()))
        persistNow()
    }

    private fun persistNow() {
        persist?.invoke(snapshot())
    }

    /** Current halt flags + daily PnL in their persisted shape. */
    fun snapshot(): com.qkt.persistence.PersistedRiskState {
        val daily = dailyPnLTracker.snapshot()
        return com.qkt.persistence.PersistedRiskState(
            epochDay = daily.epochDay,
            realizedToday = daily.global,
            perStrategyRealizedToday = daily.byStrategy,
            halted = halted,
            haltReason = haltReason,
            haltScope = haltScope.name,
            haltEpochDay = haltEpochDay,
            strategyHalts =
                haltedStrategies.map { (id, info) ->
                    com.qkt.persistence.PersistedStrategyHalt(id, info.reason, info.scope.name, info.epochDay)
                },
        )
    }

    /**
     * Restore a persisted snapshot at boot. DAILY-scoped halts from a PAST UTC day are
     * NOT restored — they auto-resume at midnight (#354) and a restart must not revive
     * them; PERSISTENT halts and same-day DAILY halts come back exactly as they were.
     * Daily PnL only carries over when the snapshot is from today. No events publish —
     * these are pre-existing facts, not new state changes.
     */
    @Synchronized
    fun restore(persisted: com.qkt.persistence.PersistedRiskState) {
        dailyPnLTracker.restore(
            DailyPnLSnapshot(persisted.epochDay, persisted.realizedToday, persisted.perStrategyRealizedToday),
        )
        val today = epochDay()
        val scope = runCatching { HaltScope.valueOf(persisted.haltScope) }.getOrDefault(HaltScope.PERSISTENT)
        if (persisted.halted && (scope == HaltScope.PERSISTENT || persisted.haltEpochDay >= today)) {
            halted = true
            haltReason = persisted.haltReason
            haltScope = scope
            haltEpochDay = persisted.haltEpochDay
        }
        for (h in persisted.strategyHalts) {
            val hScope = runCatching { HaltScope.valueOf(h.scope) }.getOrDefault(HaltScope.PERSISTENT)
            if (hScope == HaltScope.PERSISTENT || h.epochDay >= today) {
                haltedStrategies[h.strategyId] = HaltInfo(h.reason, hScope, h.epochDay)
            }
        }
    }

    companion object {
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
