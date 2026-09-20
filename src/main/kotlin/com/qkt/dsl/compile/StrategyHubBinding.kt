package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.strategy.StrategyContext

/**
 * The candle hub and strategy context a compiled strategy is bound to. Set once by
 * [DslCompiledStrategy.bindToHub] and read by the collaborators that act outside a bar close:
 * schedules and exit hooks.
 */
internal class StrategyHubBinding(
    private val streams: Map<String, HubKey>,
) {
    var hubBound: Boolean = false
        private set
    var boundHub: CandleHub? = null
        private set
    var boundContext: StrategyContext? = null
        private set

    fun bind(
        hub: CandleHub,
        ctx: StrategyContext,
    ) {
        hubBound = true
        boundHub = hub
        boundContext = ctx
    }

    /** The latest closed candle of the first declared stream that has one, or null before any close. */
    fun latestKnownCandle(hub: CandleHub): Candle? {
        for ((_, key) in streams) {
            val c = hub.latest(key)
            if (c != null) return c
        }
        return null
    }
}
