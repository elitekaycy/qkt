package com.qkt.dsl.compile

import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext

/** How one rule fire bears on a pending `SEQUENCE ... complete` consumption. */
internal enum class SequenceFireOutcome {
    NOT_CONSUMING,
    ACCEPTED,
    SUPPRESSED,
}

/**
 * Fires compiled rules and remembers which rule caused which signal and order, so a rejected
 * order re-arms the rule that sent it and each submitted order links back to its rule decision.
 */
internal class RuleFireLedger(
    private val strategyFingerprint: String,
    private val streams: Map<String, HubKey>,
) {
    private val ruleByOrderId: MutableMap<String, CompiledRule> = mutableMapOf()

    private data class RuleSignalCause(
        val rule: CompiledRule,
        val decisionId: String,
        val signalIndex: Int,
    )

    private val ruleBySignal: java.util.IdentityHashMap<Signal, RuleSignalCause> = java.util.IdentityHashMap()
    var ruleDecisionObserver: (RuleDecisionAudit) -> Unit = {}

    fun clear() {
        ruleByOrderId.clear()
    }

    fun onOrderRejected(clientOrderId: String) {
        ruleByOrderId.remove(clientOrderId)?.rearmAfterRejection()
    }

    fun onOrderSubmitted(
        signal: Signal,
        clientOrderId: String,
    ): DecisionOrderLink? {
        val cause = ruleBySignal.remove(signal) ?: return null
        ruleByOrderId[clientOrderId] = cause.rule
        if (signal is Signal.Submit) {
            correlationIds(signal.request).forEach { ruleByOrderId[it] = cause.rule }
        }
        return DecisionOrderLink(
            decisionId = cause.decisionId,
            ruleId = cause.rule.ruleId,
            signalIndex = cause.signalIndex,
            orderId = clientOrderId,
        )
    }

    fun onOrderTerminal(clientOrderId: String) {
        ruleByOrderId.remove(clientOrderId)
    }

    /**
     * Fires [rule] and seals its edge from the submission outcome: an edge whose signals
     * were all recorded as suppressed (none accepted) re-arms and may fire again next
     * bar; anything else — an accepted signal, a signal-less fire, or a consumer that
     * records no outcomes at all — consumes the edge. See [CompiledRule.commitFire].
     */
    fun fireAndCommit(
        rule: CompiledRule,
        ec: EvalContext,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ): SequenceFireOutcome {
        val fired = rule.fire(ec, ctx)
        if (fired.isEmpty()) {
            val committed = rule.commitFire(true)
            if (committed != null) ruleDecisionObserver(ruleDecision(rule, ec, ctx, signalCount = 0))
            return when (committed) {
                RuleCommitOutcome.ACCEPTED ->
                    if (rule.consumesSequenceCompletion) {
                        SequenceFireOutcome.ACCEPTED
                    } else {
                        SequenceFireOutcome.NOT_CONSUMING
                    }
                RuleCommitOutcome.REARMED ->
                    if (rule.consumesSequenceCompletion) {
                        SequenceFireOutcome.SUPPRESSED
                    } else {
                        SequenceFireOutcome.NOT_CONSUMING
                    }
                null -> SequenceFireOutcome.NOT_CONSUMING
            }
        }
        val orderIds =
            fired
                .filterIsInstance<Signal.Submit>()
                .flatMap { correlationIds(it.request) }
        val decision = ruleDecision(rule, ec, ctx, fired.size)
        ruleDecisionObserver(decision)
        fired.forEachIndexed { index, signal ->
            ruleBySignal[signal] = RuleSignalCause(rule, decision.decisionId, index)
        }
        orderIds.forEach { ruleByOrderId[it] = rule }
        val acceptedBefore = ctx.submissions.accepted
        val suppressedBefore = ctx.submissions.suppressed
        for (sig in fired) {
            emit(sig)
            ruleBySignal.remove(sig)
        }
        val anyAccepted = ctx.submissions.accepted > acceptedBefore
        val anySuppressed = ctx.submissions.suppressed > suppressedBefore
        val accepted = anyAccepted || !anySuppressed
        val committed = rule.commitFire(accepted)
        if (committed == RuleCommitOutcome.REARMED) {
            fired.forEach(ruleBySignal::remove)
            orderIds.forEach(ruleByOrderId::remove)
        }
        return when {
            committed == null || !rule.consumesSequenceCompletion -> SequenceFireOutcome.NOT_CONSUMING
            committed == RuleCommitOutcome.ACCEPTED -> SequenceFireOutcome.ACCEPTED
            else -> SequenceFireOutcome.SUPPRESSED
        }
    }

    private fun ruleDecision(
        rule: CompiledRule,
        ec: EvalContext,
        ctx: StrategyContext,
        signalCount: Int,
    ): RuleDecisionAudit {
        val key = streams.getValue(rule.ruleAlias)
        val decisionId = "${ctx.strategyId}:$strategyFingerprint:${rule.ruleFingerprint}:${ec.candle.endTime}"
        return RuleDecisionAudit(
            decisionId = decisionId,
            ruleId = rule.ruleId,
            strategyFingerprint = strategyFingerprint,
            ruleFingerprint = rule.ruleFingerprint,
            conditionFingerprint = rule.conditionFingerprint,
            conditionResult = true,
            alias = rule.ruleAlias,
            key = key,
            candle = ec.candle,
            signalCount = signalCount,
        )
    }
}

private fun correlationIds(request: com.qkt.execution.OrderRequest): List<String> =
    when (request) {
        is com.qkt.execution.OrderRequest.Bracket ->
            listOf(request.id, request.entry.id)
        is com.qkt.execution.OrderRequest.StandaloneOCO ->
            listOf(request.id) + correlationIds(request.leg1) + correlationIds(request.leg2)
        is com.qkt.execution.OrderRequest.OTO ->
            listOf(request.id) + correlationIds(request.parent)
        else -> listOf(request.id)
    }
