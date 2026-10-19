package com.qkt.trade.session

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext

/**
 * Session observer strategy: emits nothing, records every closed candle into the
 * session's [BarHistory] and caches the last tick per symbol so `bot quote` can be
 * answered at the replay cursor. Registered under a reserved identity the report
 * step filters out.
 */
class BotSessionRecorder(
    private val history: BarHistory,
) : Strategy {
    private val ticks = java.util.concurrent.ConcurrentHashMap<String, Tick>()
    private val seen =
        java.util.concurrent.atomic
            .AtomicLong()

    /** Ticks the engine has handed the session so far: where an intent arriving now enters the replay. */
    val ticksSeen: Long get() = seen.get()

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        ticks[tick.symbol] = tick
        seen.incrementAndGet()
    }

    override fun onCandle(
        candle: Candle,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        // Closed bars reach the history per series from the session's own hub (see BotSessionIdentities).
    }

    /** Most recent tick seen for [symbol], or null before the first. */
    fun lastTick(symbol: String): Tick? = ticks[symbol]

    companion object {
        /** Reserved strategy id for the recorder; excluded from report artifacts. */
        const val ID = "__session_recorder"
    }
}
