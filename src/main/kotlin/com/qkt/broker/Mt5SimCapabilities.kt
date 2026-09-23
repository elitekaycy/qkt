package com.qkt.broker

/** What [MT5BrokerSimulator] supports: it splits brackets into engine-held exits. */
internal val MT5_SIM_CAPABILITIES: Set<OrderTypeCapability> =
    setOf(
        OrderTypeCapability.MARKET,
        OrderTypeCapability.LIMIT,
        OrderTypeCapability.STOP,
        OrderTypeCapability.STOP_LIMIT,
        OrderTypeCapability.IF_TOUCHED,
        OrderTypeCapability.MULTI_POSITION_PER_SYMBOL,
    )
