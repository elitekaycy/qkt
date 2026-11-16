package com.qkt.instrument

/**
 * An event slot symbol, `VENUE:FAMILY@<event>.<strike>` (`KALSHI:KXINXU@front.p70`): one stable stream of the event
 * family [root] that follows whichever contract its selectors ([SlotSelectors]) pick as contracts come and go. The
 * spec stays inside the symbol, so a slot is a file-safe qkt symbol and a strategy's AST is unchanged by it; a
 * continuous future (`CME:ES@front`) never carries the strike part, which is how the two are told apart.
 */
data class SlotSymbol(
    val root: String,
    val event: String,
    val strike: String,
) {
    /** How many open events after the front one this slot skips. */
    val eventOffset: Int get() = requireNotNull(SlotSelectors.eventOffset(event))

    /** The rule this slot's strike part picks a contract with. */
    val rule: StrikeRule get() = requireNotNull(SlotSelectors.strike(strike))

    /** The qkt symbol: `VENUE:FAMILY@<event>.<strike>`. */
    val symbol: String get() = "$root@$event.$strike"

    companion object {
        /** [qktSymbol] as a slot, or null when it is not one (any other symbol, a continuous future included). */
        fun parse(qktSymbol: String): SlotSymbol? {
            val at = qktSymbol.indexOf('@')
            if (at < 0) return null
            val spec = qktSymbol.substring(at + 1)
            val event = spec.substringBefore('.', missingDelimiterValue = "")
            val strike = spec.substringAfter('.', missingDelimiterValue = "")
            val root = qktSymbol.substring(0, at)
            val valid =
                ':' in root &&
                    SlotSelectors.eventOffset(event) != null &&
                    SlotSelectors.strike(strike) != null
            return if (valid) SlotSymbol(root, event, strike) else null
        }

        /** True when [qktSymbol] is an event slot. */
        fun isSlot(qktSymbol: String): Boolean = parse(qktSymbol) != null
    }
}
