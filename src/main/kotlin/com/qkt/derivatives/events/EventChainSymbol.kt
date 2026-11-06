package com.qkt.derivatives.events

import com.qkt.instrument.SlotSelectors

/**
 * An event analytics stream symbol, `CHAIN:<VENUE>.<FAMILY>.<metric>.<event>`
 * (`CHAIN:KALSHI.KXINXU.implied_median.front`): [metric] ([EventChainMetrics]) of the event family [root]
 * (`KALSHI:KXINXU`) over its [event] (`front` or `next`, as slots pick events). It shares the `CHAIN:` prefix with
 * option chain analytics and is told apart by its last part, an event selector where an option stream has a tenor.
 */
data class EventChainSymbol(
    val root: String,
    val metric: String,
    val event: String,
) {
    /** How many open events after the front one this stream reads. */
    val eventOffset: Int get() = requireNotNull(SlotSelectors.eventOffset(event))

    companion object {
        private const val PREFIX = "CHAIN:"

        /** True when [qktSymbol] has an event stream's shape: `CHAIN:` and four parts, the last an event selector. */
        fun isEventChain(qktSymbol: String): Boolean {
            if (!qktSymbol.startsWith(PREFIX)) return false
            val parts = qktSymbol.removePrefix(PREFIX).split('.')
            return parts.size == 4 && SlotSelectors.eventOffset(parts[3]) != null
        }

        /** [qktSymbol] as an event stream; fails naming the metrics when its metric is unknown. */
        fun parse(qktSymbol: String): EventChainSymbol {
            require(isEventChain(qktSymbol)) { "$qktSymbol must be CHAIN:<VENUE>.<FAMILY>.<metric>.<front|next>" }
            val (venue, family, metric, event) = qktSymbol.removePrefix(PREFIX).split('.')
            require(EventChainMetrics.of(metric) != null) {
                "unknown event metric '$metric' in $qktSymbol; use ${EventChainMetrics.tokens}"
            }
            return EventChainSymbol("$venue:$family", metric, event)
        }
    }
}
