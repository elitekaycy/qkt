package com.qkt.broker.continuous

import com.qkt.execution.OrderRequest
import org.slf4j.LoggerFactory

/**
 * The bound on a collared roll leg a venue left working. Such a leg is immediate-or-cancel and should end at once, but
 * a venue may not honour that (MT5's translation drops a limit's time in force), and a leg that rests would hold its
 * roll for ever. So a limit leg still out [DEADLINE_MS] after it was made is [cancel]led at the venue on the next tick,
 * and asked again each [DEADLINE_MS] until the venue answers; the answer takes the leg's usual path. Longer than a
 * gateway's 30 s of resending a submit, so a slow leg is not taken for a resting one. Nothing is asked while [waiting]
 * on a restarted venue. O(1) per tick with no leg out.
 */
internal class RollLegDeadline(
    private val legs: RollLegs,
    private val waiting: () -> Boolean,
    private val cancel: (String) -> Unit,
) {
    private val log = LoggerFactory.getLogger(RollLegDeadline::class.java)
    private val asked = HashMap<String, Long>()

    /** Sends what waited for [nowMs] ([RollLegs.retries]), then asks for the cancel of every limit leg past its deadline. */
    fun onTick(nowMs: Long) {
        if (waiting()) return
        legs.retries.fire(nowMs)
        if (!legs.anyOut) {
            if (asked.isNotEmpty()) asked.clear()
            return
        }
        for ((leg, _) in legs.inFlight) {
            if (leg !is OrderRequest.Limit || nowMs - (asked[leg.id] ?: leg.timestamp) < DEADLINE_MS) continue
            log.error("roll leg {} on {} is still working {} ms on; cancelling it", leg.id, leg.symbol, DEADLINE_MS)
            asked[leg.id] = nowMs
            cancel(leg.id)
        }
    }

    companion object {
        const val DEADLINE_MS = 60_000L
    }
}
