package com.qkt.app

import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator

object OrderManagerTier2FallbackFixtures {
    fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    val tier1Only = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT)
}
