package com.qkt.candles

import com.qkt.bus.EventBus
import com.qkt.common.Money
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

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

    private val log = org.slf4j.LoggerFactory.getLogger(CandleAggregator::class.java)

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
            if (countLateDrop) {
                droppedLateTicks++
                // A dropped late tick is a bar that silently disagrees with the venue's own, so
                // it must never be a counter nobody reads. Throttled: a feed stall drops a burst.
                if (droppedLateTicks == 1L || droppedLateTicks % LATE_DROP_LOG_EVERY == 0L) {
                    log.warn(
                        "late tick for {} stamped {} arrived after its candle closed at {} — " +
                            "that bar under-reports the venue ({} dropped so far); raise " +
                            "candle_close_grace_ms if this persists",
                        tick.symbol,
                        tick.timestamp,
                        lastClosedEnd[tick.symbol],
                        droppedLateTicks,
                    )
                }
            }
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
     * Adopt [partial] as this window's open candle, so a start part-way through a window still
     * closes a bar with the true open, high and low (#1196). Ignored once the symbol has an open
     * or later candle. Seeded volume counts as ticks, which is how a size-less venue defines it.
     */
    fun seedForming(partial: Candle) {
        val s = partial.symbol
        if (s in open || partial.startTime < (lastClosedEnd[s] ?: Long.MIN_VALUE)) return
        val ticks = partial.volume.toInt().coerceAtLeast(1)
        open[s] =
            MutableCandle(
                s,
                partial.open,
                partial.high,
                partial.low,
                partial.close,
                partial.volume,
                ticks,
                false,
                partial.startTime,
                partial.endTime,
                null,
                null,
            )
    }

    /**
     * Close every in-progress candle whose window already ended at [nowMs] — the
     * time-driven close for quiet symbols. Without it a candle only closes when the
     * NEXT tick arrives: on a thin session edge the last bar never closes, its rules
     * never evaluate, and partial sync windows are immortal. The live heartbeat drives
     * this from the wall clock; replay drives it from each tick's event time (#1134).
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

    companion object {
        /** Throttle for the late-drop warning: a stalled feed drops a burst, not one tick. */
        private const val LATE_DROP_LOG_EVERY: Long = 100L

        fun standalone(
            window: TimeWindow,
            barVolume: BarVolumeSource? = null,
            onClose: (Candle) -> Unit,
        ): CandleAggregator = CandleAggregator(window = window, emit = onClose, bus = null, barVolume = barVolume)
    }
}
