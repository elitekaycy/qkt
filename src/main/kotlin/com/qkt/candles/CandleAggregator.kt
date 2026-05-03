package com.qkt.candles

import com.qkt.bus.EventBus
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

class CandleAggregator(
    private val bus: EventBus,
    private val window: TimeWindow,
) {
    private val open = mutableMapOf<String, MutableCandle>()

    init {
        bus.subscribe<TickEvent> { event -> handle(event.tick) }
    }

    private fun handle(tick: Tick) {
        val state = open[tick.symbol]
        if (state == null) {
            open[tick.symbol] = newState(tick)
            return
        }
        if (tick.timestamp >= state.endTime) {
            bus.publish(CandleEvent(state.toCandle()))
            open[tick.symbol] = newState(tick)
            return
        }
        state.update(tick)
    }

    private fun newState(tick: Tick): MutableCandle {
        val start = window.windowStartFor(tick.timestamp)
        val end = start + window.durationMs
        return MutableCandle(
            symbol = tick.symbol,
            open = tick.price,
            high = tick.price,
            low = tick.price,
            close = tick.price,
            volume = tick.volume ?: 0.0,
            startTime = start,
            endTime = end,
        )
    }

    private class MutableCandle(
        val symbol: String,
        val open: Double,
        var high: Double,
        var low: Double,
        var close: Double,
        var volume: Double,
        val startTime: Long,
        val endTime: Long,
    ) {
        fun update(tick: Tick) {
            if (tick.price > high) high = tick.price
            if (tick.price < low) low = tick.price
            close = tick.price
            if (tick.volume != null) volume += tick.volume
        }

        fun toCandle(): Candle = Candle(symbol, open, high, low, close, volume, startTime, endTime)
    }
}
