package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.HubKey
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.UnsupportedDataException
import org.slf4j.LoggerFactory

/**
 * How a symbol-set member that joins mid-run reads the history it lacks (runtime membership plan §4.3): up to `count`
 * closed bars of its stream ending at or before `endMs`, from [source] through the loader declared streams warm from,
 * so a backtest reads its store and a live session its venue's bars. Short history is not padded, and a source with
 * no bars for the stream gives none: the member then warms from the live closes, as a declared stream would.
 *
 * A read that fails is logged and gives no bars (a source that serves no bars at all is not a failure). Unlike a
 * declared stream's warmup at the start, a join never waits for a venue whose history is behind: it runs on the
 * engine loop, where a wait would hold every member's exits.
 */
internal fun setMemberWarmup(source: MarketSource): (HubKey, Int, Long) -> List<Candle> {
    val history = WarmupHistoryLoader(source, WarmupSettle(maxGapMs = 0L))
    return { key, count, endMs ->
        try {
            history.loadAvailable(key.qktSymbol, TimeWindow.parse(key.timeframe), count, endMs)
        } catch (e: UnsupportedDataException) {
            emptyList()
        } catch (e: Exception) {
            log.warn("set member {}: no history read: {}", key, e.message)
            emptyList()
        }
    }
}

private val log = LoggerFactory.getLogger("com.qkt.app.SetMemberWarmup")
