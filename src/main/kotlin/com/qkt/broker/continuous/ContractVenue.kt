package com.qkt.broker.continuous

import com.qkt.broker.Broker
import com.qkt.marketdata.Tick

/**
 * The contract-level venue a continuous stream executes on: its [broker], and [onTick], which hands
 * it each contract tick the stream derives (a simulated exchange matches on them; a live venue has
 * its own feed and ignores them).
 */
class ContractVenue(
    val broker: Broker,
    val onTick: (Tick) -> Unit = {},
)
