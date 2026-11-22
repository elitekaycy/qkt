package com.qkt.observe.insights

import com.qkt.events.SetMembershipChanged

/**
 * Insights translation for moving symbol sets: each [SetMembershipChanged] becomes one `set.membership`
 * envelope (qkt-insights contract 1.7.0). Mixed into [InsightsTranslate]; pure.
 */
interface SetInsights {
    /**
     * The `set.membership` envelope of [e]: its strategy, set, member, change (lower case), rank when ranked, and
     * the close of the window whose evaluation made the change (`closeTs`), e.g. a join at rank 2 is
     * `{"set": "movers", "member": "BINANCE:BTCUSDT", "change": "join", "rank": 2, "closeTs": 3600000}`. The
     * contract's optional `reason` is not sent: the event carries no cause of its own.
     */
    fun fromSetMembershipChanged(e: SetMembershipChanged): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "set.membership",
            buildMap {
                put("strategyId", e.strategyId)
                put("set", e.set)
                put("member", e.member)
                put("change", e.change.name.lowercase())
                e.rank?.let { put("rank", it) }
                put("closeTs", e.windowEndMs)
            },
        )
}
