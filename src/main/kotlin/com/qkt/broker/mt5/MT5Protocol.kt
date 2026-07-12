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
     * STOP and LIMIT map to order types accepted by the deployed gateway. STOP_LIMIT
     * and TRAILING_STOP deliberately remain absent: the gateway neither accepts the
     * stop-limit type nor applies the translator's trailing-distance field, so the
     * engine must emulate those shapes. Brokers can subtract supported capabilities
     * via [MT5BrokerProfile.capabilityRestrictions] for venue-specific restrictions.
     *
     * Pending-order fill-event lifecycle (detecting fills via position deltas, OCO
     * sibling cancel-on-fill via ticket correlation) is Phase 26c. Until then,
     * placement succeeds but qkt-side fill events for pending shapes arrive lazily
     * via the position poller.
     */
    val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.BRACKET,
            OrderTypeCapability.STOP,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.OCO,
            OrderTypeCapability.MODIFY,
            OrderTypeCapability.MULTI_POSITION_PER_SYMBOL,
            OrderTypeCapability.POSITION_MODIFY,
        )
}
