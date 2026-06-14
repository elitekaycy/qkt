package com.qkt.marketdata.macro

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.store.macro.MacroPoint

/**
 * A [TickFeed] that replays a daily macro series point-in-time. Each stored [MacroPoint] is emitted
 * as a [Tick] carrying the series value as `price`, stamped at the instant the value was published
 * ([ReleaseSchedule]) — not the observation date. Only ticks whose release falls in `[fromMs, toMs)`
 * are emitted, in release-time order, so a strategy never sees a value before it was knowable.
 *
 * Holds between releases by construction: the engine sees the last emitted value until the next
 * release tick arrives. Merges with symbol tick feeds via [com.qkt.marketdata.MergingTickFeed].
 */
class MacroSeriesFeed(
    qktSymbol: String,
    points: List<MacroPoint>,
    fromMs: Long,
    toMs: Long,
    lagBusinessDays: Int = 1,
    releaseUtcHour: Int = 13,
) : TickFeed {
    private val iterator: Iterator<Tick> =
        points
            .map { p ->
                Tick(
                    symbol = qktSymbol,
                    price = p.value,
                    timestamp = ReleaseSchedule.releaseTimeMs(p.date, lagBusinessDays, releaseUtcHour),
                )
            }.filter { it.timestamp in fromMs until toMs }
            .sortedBy { it.timestamp }
            .iterator()

    override fun next(): Tick? = if (iterator.hasNext()) iterator.next() else null
}
