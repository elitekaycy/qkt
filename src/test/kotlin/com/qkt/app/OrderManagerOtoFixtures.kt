package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator

object OrderManagerOtoFixtures {
    fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
}
