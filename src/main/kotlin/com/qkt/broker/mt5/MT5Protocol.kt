package com.qkt.broker.mt5

import com.qkt.broker.OrderTypeCapability

/**
 * Protocol-level capabilities for any MT5 venue.
 *
 * Same for all MT5 brokers (Exness/ICMarkets/FTMO/...) — the protocol caps what's
 * possible, individual profiles can subtract via [MT5BrokerProfile.capabilityRestrictions].
 */
object MT5Protocol {
    /** Order types every MT5 venue understands at the protocol layer. */
    val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.BRACKET,
        )
}
