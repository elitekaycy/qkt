package com.qkt.broker

/**
 * Opt-in ability of an order-entry session: venue tickets it found open at startup, each with the
 * strategy it belongs to. The live session mirrors them so its state poller can name the strategy
 * that owns a position it did not open in this run.
 */
interface TicketAttributionProvider {
    /** Venue ticket to owning strategy id. */
    fun ticketAttributions(): Map<String, String>
}
