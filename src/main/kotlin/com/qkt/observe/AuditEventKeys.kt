package com.qkt.observe

import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.Event
import com.qkt.events.FillAccountedEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.RuleDecisionEvent
import com.qkt.events.SignalEvent
import com.qkt.events.SignalSuppressedEvent
import com.qkt.events.StrategyCandleEvaluatedEvent
import com.qkt.events.StreamCandleEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.events.WarmupTickEvent

/** The owning strategy an audit line is indexed by, or null for engine-wide events. */
internal fun auditStrategyId(event: Event): String? =
    when (event) {
        is SignalEvent -> event.strategyId.takeIf { it.isNotBlank() }
        is OrderEvent -> event.request.strategyId.takeIf { it.isNotBlank() }
        is BrokerEvent.OrderEvent -> event.strategyId.takeIf { it.isNotBlank() }
        is RiskRejectedEvent -> event.request.strategyId.takeIf { it.isNotBlank() }
        is RiskEvent.Halted -> event.strategyId?.takeIf { it.isNotBlank() }
        is RiskEvent.Resumed -> event.strategyId?.takeIf { it.isNotBlank() }
        is StrategyCandleEvaluatedEvent -> event.strategyId.takeIf { it.isNotBlank() }
        is RuleDecisionEvent -> event.strategyId.takeIf { it.isNotBlank() }
        is DecisionOrderLinkedEvent -> event.strategyId.takeIf { it.isNotBlank() }
        is FillAccountedEvent -> event.strategyId.takeIf { it.isNotBlank() }
        is TradeEvent -> event.strategyId.takeIf { it.isNotBlank() }
        is SignalSuppressedEvent -> event.strategyId.takeIf { it.isNotBlank() }
        else -> null
    }

/** The client order id an audit line is indexed by, or null for events not about one order. */
internal fun auditOrderId(event: Event): String? =
    when (event) {
        is OrderEvent -> event.request.id
        is BrokerEvent.OrderEvent -> event.clientOrderId
        is TradeEvent -> event.trade.orderId
        is RiskRejectedEvent -> event.request.id
        is DecisionOrderLinkedEvent -> event.orderId
        is FillAccountedEvent -> event.orderId
        else -> null
    }

/** The instrument an audit line is indexed by, or null for events not about one symbol. */
internal fun auditSymbol(event: Event): String? =
    when (event) {
        is SignalEvent -> event.signal.symbolOrNull()
        is OrderEvent -> event.request.symbol
        is BrokerEvent.OrderFilled -> event.symbol
        is BrokerEvent.OrderPartiallyFilled -> event.symbol
        is BrokerEvent.PositionReconciled -> event.symbol
        is TradeEvent -> event.trade.symbol
        is TickEvent -> event.tick.symbol
        is WarmupTickEvent -> event.tick.symbol
        is CandleEvent -> event.candle.symbol
        is StreamCandleEvent -> event.candle.symbol
        is StrategyCandleEvaluatedEvent -> event.candle.symbol
        is RuleDecisionEvent -> event.candle.symbol
        is FillAccountedEvent -> event.symbol
        is RiskRejectedEvent -> event.request.symbol
        else -> null
    }

private fun com.qkt.strategy.Signal.symbolOrNull(): String? =
    when (this) {
        is com.qkt.strategy.Signal.Buy -> symbol
        is com.qkt.strategy.Signal.Sell -> symbol
        is com.qkt.strategy.Signal.Submit -> request.symbol
        is com.qkt.strategy.Signal.CancelPendingForSymbol -> symbol
        is com.qkt.strategy.Signal.ArmLatch -> null
        is com.qkt.strategy.Signal.Suppressed -> symbol
    }
