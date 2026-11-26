package com.qkt.dsl.sets

import com.qkt.instrument.universe.CatalogField
import com.qkt.instrument.universe.MarketFacts
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/**
 * The watched tier of a set that filters or ranks on market fields (runtime membership plan, step 5): one light
 * record per watched symbol and window of [windowMs] (epoch aligned), folded from its ticks: the last price, the
 * volume traded, the last bid and ask. [asOf] reads, for each symbol, the record of the last window that ended at or
 * before a close, so a set evaluated at a close sees every tick before it and none after, whichever order the
 * close and the next tick arrive in. Only symbols [watches] accepts are watched: a set watches its whole venue, so a
 * contract a live session subscribes after it started has figures by the time it can be ranked.
 */
internal class WatchedWindows(
    private val windowMs: Long,
    private val watches: (String) -> Boolean,
) {
    private class Window(
        val endMs: Long,
        var close: BigDecimal,
        var volume: BigDecimal?,
        var bid: BigDecimal?,
        var ask: BigDecimal?,
    )

    private val latest = HashMap<String, Window>()
    private val previous = HashMap<String, Window>()

    /** Folds [tick] into its symbol's record of the window it falls in; a tick of an unwatched symbol is ignored. */
    fun onTick(tick: Tick) {
        if (!watches(tick.symbol)) return
        val endMs = tick.timestamp - Math.floorMod(tick.timestamp, windowMs) + windowMs
        val window = latest[tick.symbol]
        if (window == null || window.endMs != endMs) {
            window?.let { previous[tick.symbol] = it }
            latest[tick.symbol] = Window(endMs, tick.price, tick.volume, tick.bid, tick.ask)
            return
        }
        window.close = tick.price
        tick.volume?.let { window.volume = (window.volume ?: BigDecimal.ZERO).add(it) }
        tick.bid?.let { window.bid = it }
        tick.ask?.let { window.ask = it }
    }

    /** The figures of each watched symbol's last window that ended at or before [closeMs]. */
    fun asOf(closeMs: Long): MarketFacts =
        MarketFacts { field, symbol ->
            val window =
                latest[symbol]?.takeIf { it.endMs <= closeMs } ?: previous[symbol]?.takeIf { it.endMs <= closeMs }
            window?.let {
                when (field) {
                    CatalogField.CLOSE -> it.close
                    CatalogField.VOLUME -> it.volume
                    CatalogField.SPREAD -> it.ask?.let { ask -> it.bid?.let { bid -> ask.subtract(bid) } }
                    else -> null
                }
            }
        }
}
