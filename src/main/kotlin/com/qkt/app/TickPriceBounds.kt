package com.qkt.app

import com.qkt.instrument.EventTerms
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import java.util.concurrent.ConcurrentHashMap

/**
 * The prices a symbol's ticks may legally carry beyond the positive-price floor, from what [instruments] says the
 * symbol is. An option's price may be zero (its settlement print when it expires worthless, spec E19), and so may an
 * event contract's (a NO-certain market); an event contract's prices never exceed its payout, so a tick above it is
 * malformed.
 *
 * A symbol's event terms are read from [instruments] once it is known there and remembered, so the per-tick cost after
 * the first known tick is one map read.
 */
internal class TickPriceBounds(
    private val instruments: InstrumentRegistry,
) {
    private val known = ConcurrentHashMap<String, Known>()

    /** Whether [symbol] may print a zero price: a catalogued option or an event contract. */
    fun zeroPriceLegal(symbol: String): Boolean =
        instruments.options()?.optionRoot(symbol) != null || event(symbol) != null

    /** Whether [tick]'s price and sides are within its event contract's payout; any other symbol is unbounded. */
    fun withinPayout(tick: Tick): Boolean {
        val payout = event(tick.symbol)?.payout ?: return true
        val bid = tick.bid
        val ask = tick.ask
        return tick.price <= payout && (bid == null || bid <= payout) && (ask == null || ask <= payout)
    }

    private fun event(symbol: String): EventTerms? {
        known[symbol]?.let { return it.terms }
        val meta = instruments.lookup(symbol) ?: return null
        return Known(meta.derivative as? EventTerms).also { known[symbol] = it }.terms
    }

    /** What a symbol known to the registry is: its event terms, or null for any other instrument. */
    private class Known(
        val terms: EventTerms?,
    )
}
