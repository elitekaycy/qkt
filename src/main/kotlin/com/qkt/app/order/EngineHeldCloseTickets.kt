package com.qkt.app.order

/**
 * Engine-held protective stops that must close one specific venue position: stop id -> the
 * position ticket it closes by. Such a stop fires as a close-by-ticket market order, so on a
 * hedging account it closes its own position instead of opening a counter position.
 */
internal class EngineHeldCloseTickets {
    private val ticketByStopId: MutableMap<String, String> = mutableMapOf()

    operator fun contains(stopId: String): Boolean = stopId in ticketByStopId

    operator fun set(
        stopId: String,
        ticket: String,
    ) {
        ticketByStopId[stopId] = ticket
    }

    fun ticketFor(stopId: String): String? = ticketByStopId[stopId]

    fun remove(stopId: String) {
        ticketByStopId.remove(stopId)
    }

    /** Stops armed against [ticket]; a snapshot, safe to cancel while iterating. */
    fun stopsClosing(ticket: String): Set<String> = ticketByStopId.filterValues { it == ticket }.keys
}
