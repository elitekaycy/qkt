package com.qkt.broker.mt5

import com.qkt.broker.OrderTypeCapability

/**
 * Protocol-level capabilities for any MT5 venue.
 *
 * Same for all MT5 brokers (Exness/ICMarkets/FTMO/...) — the protocol caps what's
 * possible, individual profiles can subtract via [MT5BrokerProfile.capabilityRestrictions].
 */
object MT5Protocol {
    /**
     * Order types every MT5 venue understands at the protocol layer.
     *
     * STOP, LIMIT, and STOP_LIMIT map to order types accepted by the deployed gateway.
     * TRAILING_STOP remains absent because the gateway does not apply the translator's
     * trailing-distance field, so the engine must emulate that shape. Brokers can
     * subtract supported capabilities via [MT5BrokerProfile.capabilityRestrictions]
     * for venue-specific restrictions.
     *
     * OCO deliberately remains absent: MT5 has no server-side OCO group, so qkt places
     * independent pending legs and cancels the sibling after observing a fill. Advertising
     * OCO here would falsely claim venue-atomic cancellation during that observation window.
     */
    val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.BRACKET,
            OrderTypeCapability.STOP,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.MODIFY,
            OrderTypeCapability.MULTI_POSITION_PER_SYMBOL,
            OrderTypeCapability.POSITION_MODIFY,
        )
}
