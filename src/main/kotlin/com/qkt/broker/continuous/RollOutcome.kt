package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.derivatives.futures.MeasuredRoll
import com.qkt.events.BrokerEvent
import com.qkt.events.CostIncurred
import java.math.BigDecimal

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

/** One roll in progress: its contracts, measurement, holders to carry, and what the carries left so far. */
internal class RollRun(
    val fromIndex: Int,
    val toIndex: Int,
    val measured: MeasuredRoll,
    val stopped: String,
    val resting: List<ContinuousOrder>,
    val holders: List<Map.Entry<String, BigDecimal>>,
) {
    val carried = mutableListOf<RollEntry>()
    val closes = mutableListOf<BrokerEvent.OrderFilled>()
    val failed = LinkedHashMap<String, String>()
}

/** This fill's quantity signed by its side: positive to buy, negative to sell. */
internal fun BrokerEvent.OrderFilled.signedQuantity(): BigDecimal =
    if (side ==
        Side.BUY
    ) {
        quantity
    } else {
        quantity.negate()
    }
