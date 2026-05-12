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
     * Phase 26b: STOP, LIMIT, STOP_LIMIT, OCO, and TRAILING_STOP translate natively
     * via [MT5OrderTranslator]. Brokers can subtract via
     * [MT5BrokerProfile.capabilityRestrictions] if a specific venue disables one.
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
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.OCO,
            OrderTypeCapability.TRAILING_STOP,
            OrderTypeCapability.MODIFY,
        )
}
