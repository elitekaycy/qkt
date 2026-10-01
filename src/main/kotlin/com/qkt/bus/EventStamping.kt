package com.qkt.bus

import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.CostIncurred
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
import com.qkt.events.StructureClosed
import com.qkt.events.StructureOpened
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.events.WarmupTickEvent

/**
 * Returns [event] carrying the bus timestamp [ts] and sequence id [seq]. The `when` is exhaustive
 * over the sealed [Event] hierarchy, so a new event type does not compile until it is stamped here.
 */
internal fun stampEvent(
    event: Event,
    ts: Long,
    seq: Long,
): Event =
    when (event) {
        is TickEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is WarmupTickEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is CandleEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is StreamCandleEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is StrategyCandleEvaluatedEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is RuleDecisionEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is DecisionOrderLinkedEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is FillAccountedEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is SignalEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is OrderEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is RiskRejectedEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is SignalSuppressedEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is TradeEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.OrderAccepted -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.OrderRejected -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.OrderFilled -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.OrderPartiallyFilled -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.OrderCancelled -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.OrderCancelFailed -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.OrderModified -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.BalancesUpdated -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.GatewayUnreachable -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.AccountEquityStale -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.ConnectionChanged -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.PositionReconciled -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.PositionProtectionChanged -> event.copy(timestamp = ts, sequenceId = seq)
        is BrokerEvent.PositionModificationCompleted -> event.copy(timestamp = ts, sequenceId = seq)
        is CostIncurred -> event.copy(timestamp = ts, sequenceId = seq)
        is RiskEvent.Halted -> event.copy(timestamp = ts, sequenceId = seq)
        is RiskEvent.Resumed -> event.copy(timestamp = ts, sequenceId = seq)
        is StructureOpened -> event.copy(timestamp = ts, sequenceId = seq)
        is StructureClosed -> event.copy(timestamp = ts, sequenceId = seq)
    }
