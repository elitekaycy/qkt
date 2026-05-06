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

class RiskState(
    pnl: PnLProvider,
    strategyPnL: StrategyPnL,
    private val clock: Clock,
    private val bus: EventBus,
) {
    val equityTracker: EquityTracker = EquityTracker(pnl, strategyPnL)
    val drawdownTracker: DrawdownTracker = DrawdownTracker(equityTracker)
    val dailyPnLTracker: DailyPnLTracker = DailyPnLTracker(clock)

    @Volatile
    var halted: Boolean = false
        private set

    @Volatile
    var haltReason: String? = null
        private set

    @Volatile
    var warmupComplete: Boolean = false

    private val haltedStrategies: MutableMap<String, String> = ConcurrentHashMap()

    fun isStrategyHalted(strategyId: String): Boolean = halted || strategyId in haltedStrategies

    fun haltReasonFor(strategyId: String): String? =
        if (halted) haltReason else haltedStrategies[strategyId]

    fun onTick() {
        equityTracker.update()
    }

    fun onFill(
        strategyId: String,
        realized: BigDecimal,
    ) {
        equityTracker.update()
        equityTracker.updateStrategy(strategyId)
        dailyPnLTracker.recordRealized(strategyId, realized)
    }

    @Synchronized
    fun halt(reason: String) {
        if (halted) return
        halted = true
        haltReason = reason
        bus.publish(RiskEvent.Halted(reason = reason, strategyId = null, timestamp = clock.now()))
    }

    fun haltStrategy(
        strategyId: String,
        reason: String,
    ) {
        if (haltedStrategies.putIfAbsent(strategyId, reason) != null) return
        bus.publish(RiskEvent.Halted(reason = reason, strategyId = strategyId, timestamp = clock.now()))
    }

    @Synchronized
    fun resume() {
        if (!halted) return
        halted = false
        haltReason = null
        bus.publish(RiskEvent.Resumed(strategyId = null, timestamp = clock.now()))
    }

    fun resumeStrategy(strategyId: String) {
        if (haltedStrategies.remove(strategyId) == null) return
        bus.publish(RiskEvent.Resumed(strategyId = strategyId, timestamp = clock.now()))
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
