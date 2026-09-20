package com.qkt.observe.insights

import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.RuleDecisionEvent
import com.qkt.events.SignalEvent
import com.qkt.execution.OrderRequestEvidence
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal

/**
 * Insights translation for strategy intent: emitted signals, the DSL rule evaluations that
 * produce them, and the link from a rule decision to the order it placed. Mixed into
 * [InsightsTranslate]; pure, allocation limited to the payload map each envelope carries.
 */
interface SignalInsights {
    fun fromSignal(e: SignalEvent): InsightsEnvelope? {
        val strategyId = e.strategyId.takeIf { it.isNotBlank() }
        val (type, payload) =
            when (val s = e.signal) {
                is Signal.Buy ->
                    "signal" to
                        mapOf(
                            "intent" to "BUY",
                            "symbol" to s.symbol,
                            "side" to "BUY",
                            "qty" to s.size,
                        )
                is Signal.Sell ->
                    "signal" to
                        mapOf(
                            "intent" to "SELL",
                            "symbol" to s.symbol,
                            "side" to "SELL",
                            "qty" to s.size,
                        )
                is Signal.Submit ->
                    "signal" to
                        mapOf(
                            "intent" to "SUBMIT",
                            "symbol" to s.request.symbol,
                            "side" to s.request.side.name,
                            "qty" to s.request.quantity,
                            "orderSchemaVersion" to OrderRequestEvidence.SCHEMA_VERSION,
                            "order" to OrderRequestEvidence.payload(s.request),
                        )
                is Signal.CancelPendingForSymbol ->
                    "signal.cancel" to
                        mapOf(
                            "intent" to "CANCEL_PENDING_FOR_SYMBOL",
                            "symbol" to s.symbol,
                        )
                is Signal.ArmLatch ->
                    "signal.latch_armed" to
                        mapOf(
                            "intent" to "ARM_LATCH",
                            "reference" to s.compiled.reference.toString(),
                            "offset" to s.compiled.offset.toString(),
                            "streamAlias" to s.compiled.streamAlias,
                            "name" to s.compiled.name,
                            "armWindowMs" to s.compiled.armWindowMs,
                            "expiresAt" to e.timestamp + s.compiled.armWindowMs,
                        )
                is Signal.Suppressed ->
                    "signal.suppressed" to
                        mapOf(
                            "intent" to "SUPPRESSED",
                            "symbol" to s.symbol,
                            "reason" to s.reason,
                        )
            }
        return busEnvelope(e.sequenceId, e.timestamp, strategyId, type, payload)
    }

    /** Translate one evaluated DSL rule edge with its exact closed-bar input. */
    fun fromRuleDecision(e: RuleDecisionEvent): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "decision.rule_evaluated",
            mapOf(
                "decisionId" to e.decisionId,
                "ruleId" to e.ruleId,
                "strategyFingerprint" to e.strategyFingerprint,
                "ruleFingerprint" to e.ruleFingerprint,
                "conditionFingerprint" to e.conditionFingerprint,
                "conditionResult" to e.conditionResult,
                "alias" to e.alias,
                "broker" to e.broker,
                "timeframe" to e.timeframe,
                "signalCount" to e.signalCount,
                "candle" to candlePayload(e.candle),
            ),
        )

    /** Translate a DSL decision-to-order correlation into collector evidence. */
    fun fromDecisionOrderLinked(e: DecisionOrderLinkedEvent): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "decision.order_linked",
            mapOf(
                "decisionId" to e.decisionId,
                "ruleId" to e.ruleId,
                "signalIndex" to e.signalIndex,
                "orderId" to e.orderId,
            ),
        )
}

private fun candlePayload(candle: Candle): Map<String, Any?> =
    mapOf(
        "symbol" to candle.symbol,
        "startTimeMs" to candle.startTime,
        "endTimeMs" to candle.endTime,
        "open" to candle.open,
        "high" to candle.high,
        "low" to candle.low,
        "close" to candle.close,
        "volume" to candle.volume,
        "bid" to candle.bid,
        "ask" to candle.ask,
    )
