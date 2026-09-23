package com.qkt.broker

/**
 * What [MT5BrokerSimulator] supports. It splits brackets into engine-held exits, yet live MT5 ships
 * a bracket's placeholder SL/TP with the entry and validates it at submit, so the simulator refuses
 * what that venue refuses.
 */
internal val MT5_SIM_CAPABILITIES: Set<OrderTypeCapability> =
    setOf(
        OrderTypeCapability.MARKET,
        OrderTypeCapability.LIMIT,
        OrderTypeCapability.STOP,
        OrderTypeCapability.STOP_LIMIT,
        OrderTypeCapability.IF_TOUCHED,
        OrderTypeCapability.MULTI_POSITION_PER_SYMBOL,
        OrderTypeCapability.VALIDATES_SUBMITTED_PROTECTION,
    )
