package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed

/**
 * Time-window slices of one tick day stored as text, which cannot seek. A replay asks for them in time order, so the
 * day stays open and each slice continues where the last stopped; a slice starting before that point reopens it.
 * One pass over the day, instead of a scan from its start for every bar.
 */
internal class CsvDaySlices(
    private val open: () -> TickFeed,
) {
    private var feed: TickFeed? = null
    private var pending: Tick? = null
    private var position = Long.MIN_VALUE

    /** The ticks in `[fromMs, toMs)`, in file order. */
    fun slice(
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> =
        sequence {
            if (feed == null || fromMs < position) reopen()
            position = fromMs
            while (true) {
                val t = pending ?: feed?.next() ?: break
                pending = null
                if (t.timestamp < fromMs) continue
                if (t.timestamp >= toMs) {
                    pending = t
                    break
                }
                yield(t)
            }
        }

    private fun reopen() {
        feed?.close()
        feed = open()
        pending = null
    }

    fun close() {
        feed?.close()
        feed = null
    }
}
