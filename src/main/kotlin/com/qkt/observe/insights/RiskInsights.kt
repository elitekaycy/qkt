package com.qkt.observe.insights

import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalSuppressedEvent
import com.qkt.execution.OrderRequestEvidence
import com.qkt.strategy.targetSymbol

/**
 * Insights translation for risk outcomes: suppressed signals, rejected orders, and
 * strategy halts and resumes. Mixed into [InsightsTranslate]; pure, allocation limited to
 * the payload map each envelope carries.
 */
interface RiskInsights {
    fun fromSignalSuppressed(e: SignalSuppressedEvent): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "signal.suppressed",
            mapOf(
                "reason" to e.reason,
                "symbol" to e.signal.targetSymbol(),
                "kind" to e.signal::class.simpleName,
            ),
        )

    fun fromRiskRejected(e: RiskRejectedEvent): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.request.strategyId,
            "risk.rejected",
            mapOf(
                "reason" to e.reason,
                "symbol" to e.request.symbol,
                "side" to e.request.side.name,
                "qty" to e.request.quantity,
                "orderSchemaVersion" to OrderRequestEvidence.SCHEMA_VERSION,
                "order" to OrderRequestEvidence.payload(e.request),
            ),
        )

    fun fromRiskHalted(e: RiskEvent.Halted): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "risk.halted",
            // `persistent` is what a dashboard needs to say "stays halted until someone runs qkt resume".
            mapOf(
                "strategyId" to e.strategyId,
                "reason" to e.reason,
                "scope" to e.scope,
                "persistent" to (e.scope == "PERSISTENT"),
            ),
        )

    fun fromRiskResumed(e: RiskEvent.Resumed): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "risk.resumed",
            mapOf("strategyId" to e.strategyId),
        )
}
