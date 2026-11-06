package com.qkt.marketdata.source

import com.qkt.instrument.FamilyContract
import com.qkt.instrument.SlotSymbol
import com.qkt.marketdata.Tick

/**
 * One event slot's ticks built from its family's merged quote ticks ([SlotFollower]): each tick of the followed
 * contract is repeated under the slot's symbol, unadjusted, and the pick of each window is reported to [record]. The
 * family's own ticks pass through when [passes] says so, so one feed carries the contracts the slot trades.
 */
internal class SlotTicks(
    private val slot: SlotSymbol,
    private val windowMs: Long,
    private val contracts: List<FamilyContract>,
    private val passes: (String) -> Boolean,
    private val record: (windowStartMs: Long, contract: String?) -> Unit,
) {
    fun over(family: Sequence<Tick>): Sequence<Tick> =
        sequence {
            val follower = SlotFollower(slot, windowMs, { contracts }, { true }, record)
            for (tick in family) {
                val copy = follower.step(tick)
                if (passes(tick.symbol)) yield(tick)
                if (copy != null) yield(copy)
            }
        }
}
