package com.qkt.connector.mt5

import com.qkt.broker.VenueOrderCancel
import org.slf4j.LoggerFactory

/** Cancels a resting MT5 order by ticket straight at the gateway, e.g. `DELETE /orders/3260244759`. */
internal class MT5VenueOrderCancel(
    private val client: MT5Client,
    private val profileName: String,
) : VenueOrderCancel {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    override fun cancelVenueOrder(ticket: String): Boolean {
        val venueTicket = ticket.toLongOrNull() ?: return false
        return runCatching { client.cancelOrder(venueTicket) }
            .onFailure { log.warn("MT5Broker $profileName cancel of venue order $ticket failed: ${it.message}") }
            .isSuccess
    }
}
