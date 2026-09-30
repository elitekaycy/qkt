package com.qkt.broker.continuous

import com.qkt.events.BrokerEvent
import com.qkt.events.CostIncurred

/**
 * What a roll left behind, for the lane to apply before the engine hears of it: strategies
 * [stopped] on the stream (with the reason), the venue [closes] of positions the new contract
 * refused, and the [costs] of the positions it carried.
 */
internal data class RollOutcome(
    val stopped: Map<String, String>,
    val closes: List<BrokerEvent.OrderFilled>,
    val costs: List<CostIncurred>,
)
