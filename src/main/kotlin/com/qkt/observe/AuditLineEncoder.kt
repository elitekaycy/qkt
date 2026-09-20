package com.qkt.observe

import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.Event
import com.qkt.events.FillAccountedEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.RuleDecisionEvent
import com.qkt.events.SignalEvent
import com.qkt.events.StrategyCandleEvaluatedEvent
import com.qkt.events.StreamCandleEvent
import com.qkt.events.TickEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.strategy.Signal

/**
 * Serializes one stamped bus event into its engine-audit JSONL line, trailing newline
 * included: the common identity fields, the structured block for the event's family, and
 * the stringified payload for events whose structured block is not complete.
 */
internal fun encodeAuditLine(event: Event): String =
    buildString {
        append("{\"v\":1")
        append(",\"ts\":").append(event.timestamp)
        append(",\"seq\":").append(event.sequenceId)
        append(",\"eventType\":").append(jsonString(eventType(event)))
        auditStrategyId(event)?.let { append(",\"strategyId\":").append(jsonString(it)) }
        auditOrderId(event)?.let { append(",\"orderId\":").append(jsonString(it)) }
        auditSymbol(event)?.let { append(",\"symbol\":").append(jsonString(it)) }
        if (event is TickEvent) appendTick(event.tick)
        if (event is WarmupTickEvent) {
            event.sourceTimeframeMs?.let { append(",\"sourceTimeframeMs\":").append(it) }
            appendTick(event.tick)
        }
        if (event is CandleEvent) appendCandle(event.candle)
        if (event is StreamCandleEvent) {
            append(",\"broker\":").append(jsonString(event.broker))
            append(",\"timeframe\":").append(jsonString(event.timeframe))
            appendCandle(event.candle)
        }
        if (event is StrategyCandleEvaluatedEvent) {
            append(",\"alias\":").append(jsonString(event.alias))
            append(",\"broker\":").append(jsonString(event.broker))
            append(",\"timeframe\":").append(jsonString(event.timeframe))
            append(",\"rulesEvaluated\":").append(event.rulesEvaluated)
            appendCandle(event.candle)
        }
        if (event is RuleDecisionEvent) {
            append(",\"decisionId\":").append(jsonString(event.decisionId))
            append(",\"ruleId\":").append(jsonString(event.ruleId))
            append(",\"strategyFingerprint\":").append(jsonString(event.strategyFingerprint))
            append(",\"ruleFingerprint\":").append(jsonString(event.ruleFingerprint))
            append(",\"conditionFingerprint\":").append(jsonString(event.conditionFingerprint))
            append(",\"conditionResult\":").append(event.conditionResult)
            append(",\"alias\":").append(jsonString(event.alias))
            append(",\"broker\":").append(jsonString(event.broker))
            append(",\"timeframe\":").append(jsonString(event.timeframe))
            append(",\"signalCount\":").append(event.signalCount)
            appendCandle(event.candle)
        }
        if (event is DecisionOrderLinkedEvent) {
            append(",\"decisionId\":").append(jsonString(event.decisionId))
            append(",\"ruleId\":").append(jsonString(event.ruleId))
            append(",\"signalIndex\":").append(event.signalIndex)
        }
        if (event is OrderEvent) appendOrder(event.request)
        if (event is RiskRejectedEvent) {
            append(",\"reason\":").append(jsonString(event.reason))
            appendOrder(event.request)
        }
        if (event is SignalEvent && event.signal is Signal.Submit) {
            appendOrder(event.signal.request)
        }
        if (event is FillAccountedEvent) appendAccountedFill(event)
        if (event is BrokerEvent.OrderFilled) appendFill(event)
        if (event is BrokerEvent.OrderPartiallyFilled) appendPartialFill(event)
        // Ticks and candles are fully described by their structured block and make
        // up ~99% of lines; the stringified payload doubled every one of them.
        if (!hasCompleteStructuredBlock(event)) {
            append(",\"payload\":").append(jsonString(event.toString()))
        }
        append("}\n")
    }

private fun hasCompleteStructuredBlock(event: Event): Boolean =
    event is TickEvent ||
        event is WarmupTickEvent ||
        event is CandleEvent ||
        event is StreamCandleEvent ||
        event is StrategyCandleEvaluatedEvent

private fun eventType(event: Event): String =
    event::class.qualifiedName ?: event::class.simpleName ?: event.javaClass.name
