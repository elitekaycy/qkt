package com.qkt.dsl.compile

import com.qkt.marketdata.Candle

/** Deterministic provenance for a DSL rule edge, including signal-less actions such as LOG. */
data class RuleDecisionAudit(
    val decisionId: String,
    val ruleId: String,
    val strategyFingerprint: String,
    val ruleFingerprint: String,
    val conditionFingerprint: String,
    val conditionResult: Boolean,
    val alias: String,
    val key: HubKey,
    val candle: Candle,
    val signalCount: Int,
)

/** Correlation between one signal from a DSL rule decision and its normalized order. */
data class DecisionOrderLink(
    val decisionId: String,
    val ruleId: String,
    val signalIndex: Int,
    val orderId: String,
)
