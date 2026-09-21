package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Tick

/**
 * How far back a new live subscription starts. Warmup seeds every stream with closed history up
 * to the start of the current minute; the minute in progress is built from live ticks. A session
 * that saw only the ticks after it subscribed built that minute - its first 1m bar, and the last
 * minute of every longer forming bar - with the wrong open, high and low: of fifty identical
 * strategies started together, the one that subscribed three seconds earlier computed a different
 * ATR for the rest of its lookback. So a subscription starts at the minute's start, e.g. 17:03:00
 * for one made at 17:03:52.
 *
 * Never more than [MAX_MS] back: the market-data gate reads a tick more than 60 s from the local
 * clock as a time-zone fault, and a backfilled tick must not look like one - five seconds of
 * margin for delivery. Nothing is filtered by age after it arrives: a feed whose every timestamp is
 * hours off is a mis-set `server_time_zone`, and the gate must see those ticks to say so. So a subscription made in the first 55 s of a minute sees the whole
 * minute, and one made later starts at most five seconds after the minute's open.
 */
object LiveTickBackfill {
    const val MAX_MS: Long = 55_000L

    fun since(nowMs: Long): Long = maxOf(TimeWindow.ONE_MINUTE.windowStartFor(nowMs), nowMs - MAX_MS)
}

/** The ticks a shared feed has published recently, so a late subscriber starts where an early one did. */
internal class RecentTicks {
    private val ticks = ArrayDeque<Tick>()

    fun add(
        tick: Tick,
        nowMs: Long,
    ) {
        ticks.addLast(tick)
        val horizon = nowMs - RETAIN_MS
        while (ticks.isNotEmpty() && ticks.first().timestamp < horizon) ticks.removeFirst()
    }

    /** Ticks a subscription made at [nowMs] should start with, oldest first. */
    fun backfillFor(nowMs: Long): List<Tick> {
        val since = LiveTickBackfill.since(nowMs)
        return ticks.filter { it.timestamp in since..nowMs }
    }

    private companion object {
        const val RETAIN_MS: Long = 60_000L
    }
}
