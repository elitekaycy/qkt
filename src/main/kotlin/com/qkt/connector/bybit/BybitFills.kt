package com.qkt.connector.bybit

import com.qkt.events.BrokerEvent
import java.math.BigDecimal

/**
 * The engine event one Bybit `Trade` execution means. An execution that leaves quantity to fill
 * (`leavesQty` above zero) is an [BrokerEvent.OrderPartiallyFilled] whose cumulative is the venue's own,
 * `orderQty - leavesQty`; the execution that leaves nothing completes the order, an [BrokerEvent.OrderFilled]
 * of its own quantity. A record without `leavesQty` completes its order, as before.
 */
object BybitFills {
    /** The event for [exec] of [category], owned by [strategyId]; [bookedBefore] is what qkt booked of its order. */
    fun event(
        category: String,
        exec: BybitOrderTranslator.ParsedExecution,
        strategyId: String,
        now: Long,
        bookedBefore: BigDecimal = BigDecimal.ZERO,
    ): BrokerEvent.OrderEvent {
        val symbol = BybitSymbol.toQkt(category, exec.bareSymbol)
        val leaves = exec.leavesQuantity
        if (leaves == null || leaves.signum() <= 0) {
            return BrokerEvent.OrderFilled(
                exec.clientOrderId,
                exec.brokerOrderId,
                symbol,
                exec.side,
                exec.price,
                exec.quantity,
                strategyId,
                now,
                venueCosts = exec.fee,
            )
        }
        return BrokerEvent.OrderPartiallyFilled(
            exec.clientOrderId,
            exec.brokerOrderId,
            symbol,
            exec.side,
            exec.price,
            exec.quantity,
            cumulativeFilled = exec.orderQuantity?.subtract(leaves) ?: bookedBefore.add(exec.quantity),
            strategyId = strategyId,
            timestamp = now,
            venueCosts = exec.fee,
        )
    }

    /** What of [exec]'s order executed before it, by the venue's count; zero when the record does not say. */
    fun executedBefore(exec: BybitOrderTranslator.ParsedExecution): BigDecimal {
        val total = exec.orderQuantity ?: return BigDecimal.ZERO
        return total.subtract(exec.leavesQuantity ?: BigDecimal.ZERO).subtract(exec.quantity)
    }
}
