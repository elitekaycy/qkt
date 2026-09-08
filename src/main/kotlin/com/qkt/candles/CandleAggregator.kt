package com.qkt.candles

import com.qkt.bus.EventBus
import com.qkt.common.Money
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

class CandleAggregator private constructor(
    private val window: TimeWindow,
    private val emit: (Candle) -> Unit,
    bus: EventBus?,
    /**
     * Supplies the venue's own tick volume for a closed bar when one is available. Left null
     * for tick-replay sources, where every tick is present and the aggregated count is already
     * the venue's figure. See [BarVolumeSource].
     */
    private val barVolume: BarVolumeSource? = null,
) {
    constructor(bus: EventBus, window: TimeWindow, barVolume: BarVolumeSource? = null) : this(
        window = window,
        emit = { c -> bus.publish(CandleEvent(c)) },
        bus = bus,
        barVolume = barVolume,
    )

    private val open = mutableMapOf<String, MutableCandle>()
    private val lastClosedEnd = mutableMapOf<String, Long>()

    /** Live ticks rejected after their candle was finalized; synthetic warmup reordering is excluded. */
    var droppedLateTicks: Long = 0
        private set

    init {
        bus?.subscribe<TickEvent> { event -> onTick(event.tick) }
        bus?.subscribe<WarmupTickEvent> { event -> onTick(event.tick, countLateDrop = false) }
    }

    fun onTick(tick: Tick) = onTick(tick, countLateDrop = true)

    private fun onTick(
        tick: Tick,
        countLateDrop: Boolean,
    ) {
        // A heartbeat can close a window while older ticks remain queued. Never reopen
        // or mutate an already-emitted window: doing so double-feeds every indicator.
        if (tick.timestamp < (lastClosedEnd[tick.symbol] ?: Long.MIN_VALUE)) {
            if (countLateDrop) droppedLateTicks++
            return
        }
        val state = open[tick.symbol]
        if (state == null) {
            open[tick.symbol] = newState(tick)
            return
        }
        if (tick.timestamp < state.startTime) {
            if (countLateDrop) droppedLateTicks++
            return
        }
        if (tick.timestamp >= state.endTime) {
            emitClosed(state)
            open[tick.symbol] = newState(tick)
            return
        }
        state.update(tick)
    }

    /**
     * Close every in-progress candle whose window already ended at [nowMs] — the
     * time-driven close for quiet symbols. Without it a candle only closes when the
     * NEXT tick arrives: on a thin session edge the last bar never closes, its rules
     * never evaluate, and partial sync windows are immortal. The live heartbeat drives
     * this; backtests stay purely tick-driven (event-time has no "quiet wall clock"),
     * a documented divergence (catalog row A12).
     */
    fun flushClosed(nowMs: Long) {
        val it = open.entries.iterator()
        while (it.hasNext()) {
            val (_, state) = it.next()
            if (nowMs >= state.endTime) {
                emitClosed(state)
                it.remove()
            }
        }
    }

    private fun emitClosed(state: MutableCandle) {
        emit(finalize(state.toCandle()))
        lastClosedEnd[state.symbol] = state.endTime
    }

    /** The venue's figure when it has one for exactly this bar, else what we counted. */
    private fun finalize(c: Candle): Candle {
        val venue = barVolume?.volumeFor(c.symbol, c.startTime, c.endTime) ?: return c
        return if (venue.compareTo(c.volume) == 0) c else c.copy(volume = venue)
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
            volume = tick.volume ?: Money.ZERO,
            ticks = 1,
            venueVolume = tick.volume != null && tick.volume.signum() > 0,
            startTime = start,
            endTime = end,
            bid = tick.bid,
            ask = tick.ask,
        )
    }

    private class MutableCandle(
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

    companion object {
        fun standalone(
            window: TimeWindow,
            barVolume: BarVolumeSource? = null,
            onClose: (Candle) -> Unit,
        ): CandleAggregator = CandleAggregator(window = window, emit = onClose, bus = null, barVolume = barVolume)
    }
}
