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

/**
 * One roll in progress: its contracts, measurement, the [holders] to carry in order, the resting orders
 * pulled off the old contract, and each holder's [steps] so far; what the roll left behind is read from
 * the steps. [stopped] prefixes the reason a holder was not carried.
 */
internal class RollRun(
    val fromIndex: Int,
    val toIndex: Int,
    val measured: MeasuredRoll,
    val stopped: String,
    val resting: List<ContinuousOrder>,
    val holders: List<Map.Entry<String, BigDecimal>>,
) {
    /** Each holder's step, in carry order; a holder not reached yet has none. */
    val steps = LinkedHashMap<String, CarryStep>()

    val carried: List<CarryStep.Carried> get() = steps.values.filterIsInstance<CarryStep.Carried>()

    val closes: List<BrokerEvent.OrderFilled> get() =
        steps.values.filterIsInstance<CarryStep.Stopped>().mapNotNull {
            it.close
        }

    val failed: Map<String, String>
        get() =
            steps.values.filterIsInstance<CarryStep.Stopped>().associate {
                it.strategyId to
                    "$stopped (${it.reason})"
            }
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
