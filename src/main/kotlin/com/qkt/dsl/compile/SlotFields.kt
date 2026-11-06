package com.qkt.dsl.compile

import com.qkt.instrument.SlotSymbol

/**
 * Where a stream field of an event slot reads: the contract the slot followed for its latest ticks, so `contract` and
 * the event fields (`floor_strike`, `minutes_left`, `taker_fee`, ...) describe the contract whose prices the bar holds.
 */
internal object SlotFields {
    /** The symbol a field of [symbol] reads: a slot's followed contract (null while it follows none), else [symbol]. */
    fun resolve(
        ctx: EvalContext,
        symbol: String,
    ): String? =
        if (SlotSymbol.isSlot(symbol)) ctx.strategyContext.source.slotContractAt(symbol, ctx.nowMs()) else symbol
}
