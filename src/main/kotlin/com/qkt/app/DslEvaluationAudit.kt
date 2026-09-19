package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.events.RuleDecisionEvent
import com.qkt.events.StrategyCandleEvaluatedEvent
import com.qkt.events.StreamCandleEvent

/**
 * Publishes what DSL strategies saw and decided as audit events on the bus: each declared stream's
 * closed bars ([StreamCandleEvent], once per hub key across all strategies), each candle
 * evaluation ([StrategyCandleEvaluatedEvent]) and each rule decision ([RuleDecisionEvent]).
 */
internal class DslEvaluationAudit(
    private val bus: EventBus,
    private val candleHub: CandleHub,
) {
    private val auditedHubKeys = mutableSetOf<HubKey>()

    /** Publish closed bars of [strategy]'s declared streams not already audited for another strategy. */
    fun publishStreamCandles(strategy: DslCompiledStrategy) {
        for (key in strategy.declaredStreams.values) {
            if (auditedHubKeys.add(key)) {
                candleHub.onClosed(key, STREAM_AUDIT_OWNER) { candle ->
                    bus.publish(StreamCandleEvent(key.broker, key.timeframe, candle))
                }
            }
        }
    }

    /** Publish [strategy]'s candle evaluations and rule decisions under [strategyId]. */
    fun publishEvaluations(
        strategyId: String,
        strategy: DslCompiledStrategy,
    ) {
        strategy.observeCandleEvaluations { alias, key, candle, rulesEvaluated ->
            bus.publish(
                StrategyCandleEvaluatedEvent(
                    strategyId = strategyId,
                    alias = alias,
                    broker = key.broker,
                    timeframe = key.timeframe,
                    rulesEvaluated = rulesEvaluated,
                    candle = candle,
                ),
            )
        }
        strategy.observeRuleDecisions { decision ->
            bus.publish(
                RuleDecisionEvent(
                    strategyId = strategyId,
                    decisionId = decision.decisionId,
                    ruleId = decision.ruleId,
                    strategyFingerprint = decision.strategyFingerprint,
                    ruleFingerprint = decision.ruleFingerprint,
                    conditionFingerprint = decision.conditionFingerprint,
                    conditionResult = decision.conditionResult,
                    alias = decision.alias,
                    broker = decision.key.broker,
                    timeframe = decision.key.timeframe,
                    signalCount = decision.signalCount,
                    candle = decision.candle,
                ),
            )
        }
    }

    private companion object {
        const val STREAM_AUDIT_OWNER: String = "_qkt_stream_audit"
    }
}
