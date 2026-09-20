package com.qkt.research

import com.qkt.app.TradingPipeline
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.common.Side
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Strategy
import java.math.BigDecimal

/**
 * Match the live kill-switch: a halt must remove every resting pending before another replay tick
 * can trigger it. RiskEngine only rejects new submissions; without this subscription backtests could
 * fill old entries after the halt. An account-wide halt in a multi-strategy portfolio book also
 * flattens every open leg of every child with a closing market order.
 */
internal fun subscribeHaltKillSwitch(
    bus: EventBus,
    pipeline: TradingPipeline,
    strategies: List<Pair<String, Strategy>>,
    strategyPositions: StrategyPositionTracker,
    ids: IdGenerator,
    clock: Clock,
    bookCapital: BigDecimal?,
) {
    bus.subscribe<RiskEvent.Halted> { event ->
        if (event.cancelWorkingOrders) {
            pipeline.orderManager.cancelEntriesForHalt(event.strategyId)
        }
        if (event.strategyId == null && bookCapital != null && strategies.size > 1) {
            for ((strategyId, _) in strategies) {
                for (leg in strategyPositions.allLegsFor(strategyId)) {
                    bus.publish(
                        OrderEvent(
                            OrderRequest.Market(
                                id = ids.next(),
                                symbol = leg.symbol,
                                side =
                                    if (leg.side == Side.BUY) {
                                        Side.SELL
                                    } else {
                                        Side.BUY
                                    },
                                quantity = leg.quantity,
                                timeInForce = TimeInForce.GTC,
                                timestamp = clock.now(),
                                strategyId = strategyId,
                                closesTicket = leg.brokerTicket,
                                closesLegId = leg.legId,
                            ),
                        ),
                    )
                }
            }
        }
    }
}
