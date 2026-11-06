package com.qkt.marketdata.source

import com.qkt.instrument.FamilyContract
import com.qkt.instrument.OpenEvents
import com.qkt.instrument.SlotSymbol
import com.qkt.marketdata.Tick

/**
 * One event slot's ticks built from its family's merged quote ticks. At the first tick of each `windowMs` window
 * (epoch aligned) the slot picks its contract ([SlotPick]) from the quotes seen strictly before the window's start,
 * reports the pick to [record] (null when none fits) and holds it for the window; each tick of the followed contract
 * is then repeated under the slot's symbol, unadjusted. The family's own ticks pass through when [passes] says so, so
 * one feed carries the contracts the slot trades.
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
            val rule = slot.rule
            val open = OpenEvents(contracts)
            val book = HashMap<String, Tick>()
            var followed: String? = null
            var boundary = Long.MIN_VALUE
            for (tick in family) {
                if (tick.timestamp >= boundary) {
                    val start = tick.timestamp - Math.floorMod(tick.timestamp, windowMs)
                    followed = SlotPick.pick(slot, rule, open.at(start), book::get)
                    record(start, followed)
                    boundary = start + windowMs
                }
                book[tick.symbol] = tick
                if (passes(tick.symbol)) yield(tick)
                if (tick.symbol == followed) yield(tick.copy(symbol = slot.symbol))
            }
        }
}
