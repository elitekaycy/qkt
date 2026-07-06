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
    private val persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
) {
    init {
        require(maxHistory > 0) { "TradeHistory.maxHistory must be > 0: $maxHistory" }
    }

    data class TradeOutcome(
        val timestamp: Long,
        val pnl: BigDecimal,
        val symbol: String,
    ) {
        val isWin: Boolean get() = pnl.signum() > 0
    }

    private val byStrategy: MutableMap<String, ArrayDeque<TradeOutcome>> = ConcurrentHashMap()

    /**
     * Record a fill outcome. [pnl] is the realized delta from THIS fill (positive = win,
     * negative = loss). Zero is skipped — that's a position-opening fill, not a closed trade.
     * [symbol] is the venue symbol of the fill; backs per-stream accessors like
     * `POSITION.<alias>.trades_today`.
     */
    fun recordTrade(
        strategyId: String,
        timestamp: Long,
        pnl: BigDecimal,
        symbol: String,
    ) {
        if (pnl.signum() == 0) return
        val q = byStrategy.getOrPut(strategyId) { ArrayDeque(maxHistory) }
        synchronized(q) {
            q.addLast(TradeOutcome(timestamp, pnl, symbol))
            while (q.size > maxHistory) q.removeFirst()
            persist(strategyId, q)
        }
    }

    /**
     * Rehydrate the bounded trade-outcome buffer persisted by a prior process. Call once
     * at strategy startup, before any fills are recorded.
     */
    fun restore(strategyId: String) {
        if (strategyId.isBlank()) return
        val persisted = runCatching { persistor.loadTradeHistory(strategyId) }.getOrNull() ?: return
        val q = byStrategy.getOrPut(strategyId) { ArrayDeque(maxHistory) }
        synchronized(q) {
            q.clear()
            for (outcome in persisted.outcomes.takeLast(maxHistory)) {
                if (outcome.pnl.signum() != 0) {
                    q.addLast(TradeOutcome(outcome.timestamp, outcome.pnl, outcome.symbol))
                }
            }
        }
    }

    fun lastTradeAt(strategyId: String): Long? =
        byStrategy[strategyId]?.let { synchronized(it) { it.lastOrNull()?.timestamp } }

    fun lastTradePnl(strategyId: String): BigDecimal? =
        byStrategy[strategyId]?.let { synchronized(it) { it.lastOrNull()?.pnl } }

    fun winStreak(strategyId: String): Int = streak(strategyId) { it.isWin }

    fun lossStreak(strategyId: String): Int = streak(strategyId) { !it.isWin }

    /** Realized profit accumulated during the current consecutive win streak. */
    fun banked(strategyId: String): BigDecimal {
        val q = byStrategy[strategyId] ?: return Money.ZERO
        synchronized(q) {
            var total = Money.ZERO
            for (i in q.indices.reversed()) {
                val outcome = q[i]
                if (!outcome.isWin) return total
                total = total.add(outcome.pnl)
            }
            return total
        }
    }

    /**
     * Trades recorded at or after [sinceMs]. Caller picks the cutoff — DSL `TRADES_TODAY`
     * passes UTC midnight derived from the current clock; tests pass arbitrary epochs.
     */
    fun tradesSince(
        strategyId: String,
        sinceMs: Long,
    ): Int = countWhere(strategyId) { it.timestamp >= sinceMs }

    fun winsSince(
        strategyId: String,
        sinceMs: Long,
    ): Int = countWhere(strategyId) { it.timestamp >= sinceMs && it.isWin }

    fun lossesSince(
        strategyId: String,
        sinceMs: Long,
    ): Int = countWhere(strategyId) { it.timestamp >= sinceMs && !it.isWin }

    /** Trades on [symbol] recorded at or after [sinceMs]. */
    fun tradesSinceFor(
        strategyId: String,
        symbol: String,
        sinceMs: Long,
    ): Int = countWhere(strategyId) { it.timestamp >= sinceMs && it.symbol == symbol }

    /** Timestamp of the most recent fill on [symbol], or null. */
    fun lastTradeAtFor(
        strategyId: String,
        symbol: String,
    ): Long? {
        val q = byStrategy[strategyId] ?: return null
        synchronized(q) {
            for (i in q.indices.reversed()) {
                if (q[i].symbol == symbol) return q[i].timestamp
            }
            return null
        }
    }

    private fun countWhere(
        strategyId: String,
        predicate: (TradeOutcome) -> Boolean,
    ): Int {
        val q = byStrategy[strategyId] ?: return 0
        return synchronized(q) { q.count(predicate) }
    }

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

    private fun persist(
        strategyId: String,
        outcomes: ArrayDeque<TradeOutcome>,
    ) {
        val state =
            com.qkt.persistence.PersistedTradeHistory(
                outcomes =
                    outcomes.map {
                        com.qkt.persistence.PersistedTradeOutcome(
                            timestamp = it.timestamp,
                            pnl = it.pnl,
                            symbol = it.symbol,
                        )
                    },
            )
        runCatching { persistor.saveTradeHistory(strategyId, state) }
    }
}

/** Per-strategy read-only window over [TradeHistory]. */
interface TradeHistoryView {
    fun lastTradeAt(): Long?

    fun lastTradePnl(): BigDecimal?

    fun winStreak(): Int

    fun lossStreak(): Int

    /** Realized profit accumulated during the current consecutive win streak. */
    fun banked(): BigDecimal

    /** Trades recorded since the most recent UTC midnight prior to [nowEpochMs]. */
    fun tradesToday(nowEpochMs: Long): Int

    /** Wins (positive realized P&L) since the most recent UTC midnight prior to [nowEpochMs]. */
    fun winsToday(nowEpochMs: Long): Int

    /** Losses (negative realized P&L) since the most recent UTC midnight prior to [nowEpochMs]. */
    fun lossesToday(nowEpochMs: Long): Int

    /** Trades on [symbol] since UTC midnight prior to [nowEpochMs]. */
    fun tradesTodayFor(
        symbol: String,
        nowEpochMs: Long,
    ): Int = 0

    /** Timestamp of the most recent fill on [symbol], or null. */
    fun lastTradeAtFor(symbol: String): Long? = null
}

class TradeHistoryViewImpl(
    private val history: TradeHistory,
    private val strategyId: String,
) : TradeHistoryView {
    override fun lastTradeAt(): Long? = history.lastTradeAt(strategyId)

    override fun lastTradePnl(): BigDecimal? = history.lastTradePnl(strategyId)

    override fun winStreak(): Int = history.winStreak(strategyId)

    override fun lossStreak(): Int = history.lossStreak(strategyId)

    override fun banked(): BigDecimal = history.banked(strategyId)

    override fun tradesToday(nowEpochMs: Long): Int = history.tradesSince(strategyId, utcMidnight(nowEpochMs))

    override fun winsToday(nowEpochMs: Long): Int = history.winsSince(strategyId, utcMidnight(nowEpochMs))

    override fun lossesToday(nowEpochMs: Long): Int = history.lossesSince(strategyId, utcMidnight(nowEpochMs))

    override fun tradesTodayFor(
        symbol: String,
        nowEpochMs: Long,
    ): Int = history.tradesSinceFor(strategyId, symbol, utcMidnight(nowEpochMs))

    override fun lastTradeAtFor(symbol: String): Long? = history.lastTradeAtFor(strategyId, symbol)

    /** UTC midnight epoch ms for whichever calendar day [nowEpochMs] falls on. */
    private fun utcMidnight(nowEpochMs: Long): Long = (nowEpochMs / DAY_MS) * DAY_MS

    private companion object {
        private const val DAY_MS = 86_400_000L
    }
}

/** No-op view for tests / strategies that don't care about trade history. */
class NoOpTradeHistoryView : TradeHistoryView {
    override fun lastTradeAt(): Long? = null

    override fun lastTradePnl(): BigDecimal? = null

    override fun winStreak(): Int = 0

    override fun lossStreak(): Int = 0

    override fun banked(): BigDecimal = Money.ZERO

    override fun tradesToday(nowEpochMs: Long): Int = 0

    override fun winsToday(nowEpochMs: Long): Int = 0

    override fun lossesToday(nowEpochMs: Long): Int = 0

    companion object {
        @Suppress("UnusedPrivateProperty")
        private val MONEY_ZERO: BigDecimal = Money.ZERO
    }
}
