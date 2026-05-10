package com.qkt.broker.mt5

import com.qkt.broker.OrderTypeCapability

object MT5Protocol {
    val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.BRACKET,
        )
}
