package com.qkt.marketdata.source

import com.qkt.instrument.FamilyContract
import com.qkt.instrument.OpenEvents
import com.qkt.instrument.SlotSymbol
import com.qkt.marketdata.Tick

/**
 * One event slot followed tick by tick over its family's quote ticks, backtest ([SlotTicks]) and live
 * ([LiveSlotMarketSource]) alike. At the first tick of each `windowMs` window (epoch aligned) the slot picks its
 * contract ([SlotPick]) from the quotes seen strictly before the window's start, among the family's [contracts] as
 * they are then (a live family lists new contracts while it runs; an unchanged list is the same list) and whose
 * quotes [usable] admits, reports the pick to [record] (null when none fits) and holds it for the window.
 */
internal class SlotFollower(
    private val slot: SlotSymbol,
    private val windowMs: Long,
    private val contracts: () -> List<FamilyContract>,
    private val usable: (String) -> Boolean,
    private val record: (windowStartMs: Long, contract: String?) -> Unit,
) {
    private val rule = slot.rule
    private val book = HashMap<String, Tick>()
    private var listed: List<FamilyContract>? = null
    private var open = OpenEvents(emptyList())
    private var followed: String? = null
    private var boundary = Long.MIN_VALUE

    /** Takes [tick] (of the family or not); returns it under the slot's symbol when the slot follows its contract. */
    fun step(tick: Tick): Tick? {
        if (tick.timestamp >= boundary) {
            val start = tick.timestamp - Math.floorMod(tick.timestamp, windowMs)
            val now = contracts()
            if (now !== listed) {
                listed = now
                open = OpenEvents(now)
            }
            followed = SlotPick.pick(slot, rule, open.at(start)) { symbol -> book[symbol]?.takeIf { usable(symbol) } }
            record(start, followed)
            boundary = start + windowMs
        }
        book[tick.symbol] = tick
        return if (tick.symbol == followed) tick.copy(symbol = slot.symbol) else null
    }
}
