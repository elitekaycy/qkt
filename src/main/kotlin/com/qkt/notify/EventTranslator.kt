package com.qkt.notify

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskEvent
import java.math.BigDecimal

/**
 * Pure-function translators from bus events into [NotificationEvent]s.
 *
 * The translators that need symbol/side/qty (currently only [BrokerEvent.OrderRejected])
 * take them as explicit parameters — the bus event itself doesn't carry them, so the
 * notifier stitches them in from a recent-orders context map.
 */
object EventTranslator {
    fun fromBrokerRejected(
        event: BrokerEvent.OrderRejected,
        symbol: String,
        side: Side,
        quantity: BigDecimal,
    ): NotificationEvent.OrderRejected =
        NotificationEvent.OrderRejected(
            strategyId = event.strategyId,
            symbol = symbol,
            side = side,
            quantity = quantity,
            reason = event.reason,
            timestamp = event.timestamp,
        )

    fun fromRiskHalted(event: RiskEvent.Halted): NotificationEvent.Halted =
        NotificationEvent.Halted(
            strategyId = event.strategyId,
            reason = event.reason,
            timestamp = event.timestamp,
        )

    fun fromRiskResumed(event: RiskEvent.Resumed): NotificationEvent.Resumed =
        NotificationEvent.Resumed(
            strategyId = event.strategyId,
            timestamp = event.timestamp,
        )

    fun fromPositionReconciled(
        event: BrokerEvent.PositionReconciled,
        strategyId: String,
    ): NotificationEvent.PositionReconciled =
        NotificationEvent.PositionReconciled(
            strategyId = strategyId,
            symbol = event.symbol,
            oldQty = event.oldQty,
            newQty = event.newQty,
            reason = event.reason,
            timestamp = event.timestamp,
        )
}
