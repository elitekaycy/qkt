package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.HubKey
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource

/**
 * How a symbol-set member that joins mid-run reads the history it lacks (runtime membership plan §4.3): up to `count`
 * closed bars of its stream ending at or before `endMs`, from [source] through the loader declared streams warm from,
 * so a backtest reads its store and a live session its venue's bars. Short history is not padded, and a source with
 * no bars for the stream gives none: the member then warms from the live closes, as a declared stream would.
 */
internal fun setMemberWarmup(source: MarketSource): (HubKey, Int, Long) -> List<Candle> {
    val history = WarmupHistoryLoader(source)
    return { key, count, endMs ->
        runCatching { history.loadAvailable(key.qktSymbol, TimeWindow.parse(key.timeframe), count, endMs) }
            .getOrDefault(emptyList())
    }
}
