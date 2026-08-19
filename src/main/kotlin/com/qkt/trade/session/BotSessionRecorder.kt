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
    private val ticks = mutableMapOf<String, Tick>()

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        ticks[tick.symbol] = tick
    }

    override fun onCandle(
        candle: Candle,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        history.record(candle)
    }

    /** Most recent tick seen for [symbol], or null before the first. */
    fun lastTick(symbol: String): Tick? = ticks[symbol]

    companion object {
        /** Reserved strategy id for the recorder; excluded from report artifacts. */
        const val ID = "__session_recorder"
    }
}
