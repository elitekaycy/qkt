package com.qkt.connector.gateway

import com.qkt.common.Clock
import com.qkt.events.BrokerEvent

/**
 * A gateway account's connection news, told to every broker attached to it under [label], the account's
 * config entry name uppercased (`PAPER_DERIBIT`), the label its venue state and deals carry too.
 */
internal class GatewayAlerts(
    private val label: String,
    private val routing: GatewayRouting,
    private val lock: Any,
    private val clock: Clock,
) {
    /** The gateway went unanswered [failures] times in a row. */
    fun unreachable(failures: Int) =
        synchronized(lock) { routing.broadcast(BrokerEvent.GatewayUnreachable(label, failures, clock.now())) }

    /** The event stream [connected] or dropped, for [reason]. */
    fun connection(
        connected: Boolean,
        reason: String,
    ) {
        val state = if (connected) BrokerEvent.ConnectionState.CONNECTED else BrokerEvent.ConnectionState.DISCONNECTED
        synchronized(lock) {
            routing.broadcast(BrokerEvent.ConnectionChanged(label, state, reason, timestamp = clock.now()))
        }
    }
}
