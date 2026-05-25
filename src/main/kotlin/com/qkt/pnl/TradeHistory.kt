package com.qkt.pnl

import com.qkt.common.Money
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 25-followup (#132): per-strategy ring buffer of closed-trade outcomes.
 *
 * Fed from [com.qkt.app.TradingPipeline] after every `OrderFilled` event: we
 * record the realized P&L for that fill (skipping the zero realized that mean
 * "opened a position", not "closed one"). The buffer is bounded so a long-running
 * strategy doesn't grow unbounded; only the most recent [maxHistory] outcomes are
 * retained. Streak calculations walk back from the newest entry.
 *
 * Exposed to DSL expressions via [TradeHistoryView] / `StrategyContext.tradeHistory`.
 * Accessors land as `ACCOUNT.last_trade_at`, `ACCOUNT.win_streak`, etc.
 */
class TradeHistory(
    private val maxHistory: Int = 64,
) {
    init {
        require(maxHistory > 0) { "TradeHistory.maxHistory must be > 0: $maxHistory" }
    }

    data class TradeOutcome(
        val timestamp: Long,
        val pnl: BigDecimal,
    ) {
        val isWin: Boolean get() = pnl.signum() > 0
    }

    private val byStrategy: MutableMap<String, ArrayDeque<TradeOutcome>> = ConcurrentHashMap()

    /**
     * Record a fill outcome. [pnl] is the realized delta from THIS fill (positive = win,
     * negative = loss). Zero is skipped — that's a position-opening fill, not a closed trade.
     */
    fun recordTrade(
        strategyId: String,
        timestamp: Long,
        pnl: BigDecimal,
    ) {
        if (pnl.signum() == 0) return
        val q = byStrategy.getOrPut(strategyId) { ArrayDeque(maxHistory) }
        synchronized(q) {
            q.addLast(TradeOutcome(timestamp, pnl))
            while (q.size > maxHistory) q.removeFirst()
        }
    }

    fun lastTradeAt(strategyId: String): Long? =
        byStrategy[strategyId]?.let { synchronized(it) { it.lastOrNull()?.timestamp } }

    fun lastTradePnl(strategyId: String): BigDecimal? =
        byStrategy[strategyId]?.let { synchronized(it) { it.lastOrNull()?.pnl } }

    fun winStreak(strategyId: String): Int = streak(strategyId) { it.isWin }

    fun lossStreak(strategyId: String): Int = streak(strategyId) { !it.isWin }

    private fun streak(
        strategyId: String,
        predicate: (TradeOutcome) -> Boolean,
    ): Int {
        val q = byStrategy[strategyId] ?: return 0
        synchronized(q) {
            var count = 0
            for (i in q.indices.reversed()) {
                if (predicate(q[i])) count++ else return count
            }
            return count
        }
    }
}

/** Per-strategy read-only window over [TradeHistory]. */
interface TradeHistoryView {
    fun lastTradeAt(): Long?

    fun lastTradePnl(): BigDecimal?

    fun winStreak(): Int

    fun lossStreak(): Int
}

class TradeHistoryViewImpl(
    private val history: TradeHistory,
    private val strategyId: String,
) : TradeHistoryView {
    override fun lastTradeAt(): Long? = history.lastTradeAt(strategyId)

    override fun lastTradePnl(): BigDecimal? = history.lastTradePnl(strategyId)

    override fun winStreak(): Int = history.winStreak(strategyId)

    override fun lossStreak(): Int = history.lossStreak(strategyId)
}

/** No-op view for tests / strategies that don't care about trade history. */
class NoOpTradeHistoryView : TradeHistoryView {
    override fun lastTradeAt(): Long? = null

    override fun lastTradePnl(): BigDecimal? = null

    override fun winStreak(): Int = 0

    override fun lossStreak(): Int = 0

    companion object {
        @Suppress("UnusedPrivateProperty")
        private val MONEY_ZERO: BigDecimal = Money.ZERO
    }
}
