package com.qkt.candles

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/** The candle a [CandleAggregator] is still building for one symbol. */
internal class MutableCandle(
    val symbol: String,
    val open: BigDecimal,
    var high: BigDecimal,
    var low: BigDecimal,
    var close: BigDecimal,
    var volume: BigDecimal,
    var ticks: Int,
    var venueVolume: Boolean,
    val startTime: Long,
    val endTime: Long,
    var bid: BigDecimal?,
    var ask: BigDecimal?,
) {
    fun update(tick: Tick) {
        if (tick.price > high) high = tick.price
        if (tick.price < low) low = tick.price
        close = tick.price
        ticks += 1
        if (tick.volume != null) {
            volume = volume.add(tick.volume)
            if (tick.volume.signum() > 0) venueVolume = true
        }
        bid = tick.bid
        ask = tick.ask
    }

    /**
     * Spot FX and CFD venues quote without traded size: every MT5 tick on such a symbol
     * carries `volume = 0`, so summing tick volume yields an empty bar even though the
     * venue's own history endpoint reports a `tick_volume` for the same period. A strategy
     * reading `<stream>.volume` would then see real numbers on warmup and backtest bars and
     * zero once live -- the same silent divergence class as the risk-rule defects.
     *
     * When no tick in the bar carried size, fall back to the count of ticks, which is
     * exactly how MT5 defines `tick_volume`. Venues that do report size are untouched.
     */
    fun toCandle(): Candle {
        val vol = if (venueVolume) volume else Money.of(ticks.toLong())
        return Candle(symbol, open, high, low, close, vol, startTime, endTime, bid, ask)
    }
}
