package com.qkt.app

import com.qkt.accounting.ConvertedMoney
import com.qkt.backtest.FillState
import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Trade
import java.math.BigDecimal

/**
 * Reports an accounted execution after it is booked: publishes the [TradeEvent] and hands the
 * trade to the pipeline's fill callbacks, which feed the backtest report and live journals.
 */
internal class ExecutionReporter(
    private val bus: EventBus,
    private val onFilled: (Trade, BigDecimal, String) -> Unit,
    private val onAccountedFill: (Trade, ConvertedMoney, String, FillState) -> Unit,
) {
    /** Report one accounted execution: trade event, fill callbacks, report state. */
    fun report(
        e: BrokerEvent.OrderFilled,
        accounted: AccountedExecution,
    ) {
        val a = accounted.event
        val trade = Trade(e.clientOrderId, e.symbol, e.price, e.quantity, e.side, e.timestamp)
        bus.publish(TradeEvent(trade, strategyId = e.strategyId))
        onFilled(
            trade,
            if (a.reducedExposure) a.netStrategyAccountRealized else accounted.grossStrategyAccountRealized,
            e.strategyId,
        )
        onAccountedFill(
            trade,
            accounted.converted,
            e.strategyId,
            FillState(
                accountPositionBefore = a.accountPositionBefore,
                accountPositionAfter = a.accountPositionAfter,
                strategyPositionBefore = a.strategyPositionBefore,
                strategyPositionAfter = a.strategyPositionAfter,
                contractSize = a.contractSize,
                netAccountRealized = a.netStrategyAccountRealized,
                reducedExposure = a.reducedExposure,
                legId = a.legId,
                legAction = a.legAction,
            ),
        )
    }
}
