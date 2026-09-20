package com.qkt.broker

import com.qkt.instrument.InstrumentRegistry

/**
 * Opt-in ability of an order-entry session: the venue's own contract specs for the symbols it
 * trades. The live session layers every provider's registry, so sizing and P&L use the specs the
 * venue fills at rather than a hand-maintained copy.
 */
interface InstrumentProvider {
    /** The venue's contract specs, looked up by qkt symbol. */
    fun instrumentRegistry(): InstrumentRegistry
}
