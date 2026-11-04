package com.qkt.broker.continuous

import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.RollQuoteTaps

/**
 * Keeps the contract a lane's roll closes out of heard once the stream serves the next one: while a roll is in flight
 * on [rolls], or due (the lane is still on a contract the schedule has moved past, as after a restart past the roll
 * instant), the old contract is held on [taps] so the live feed reads it, and its quotes reach [quotes]. Released once
 * the roll is done. No [taps] (a backtest, or a lane with no live feed): nothing is held and nothing is heard.
 */
internal class LaneRollTap(
    private val taps: RollQuoteTaps?,
    private val chainOf: () -> ContinuousChain,
    private val rolls: RollExecutor,
    quotes: LaneQuotes,
) {
    init {
        taps?.listen(chainOf().symbol, quotes::heardContract)
    }

    /** Holds or releases the old contract for a lane on contract [current] at [nowMs]. */
    fun sync(
        current: Int?,
        nowMs: Long,
    ) {
        val taps = taps ?: return
        val chain = chainOf()
        val due = current?.takeIf { on -> chain.indexAt(nowMs)?.let { it > on } == true }
        val from = rolls.run?.fromIndex ?: due
        if (from == null) taps.release(chain.symbol) else taps.hold(chain.symbol, chain.contractSymbol(from))
    }
}
