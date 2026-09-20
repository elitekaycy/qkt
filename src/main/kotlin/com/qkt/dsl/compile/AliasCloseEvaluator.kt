package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext

/**
 * Runs one stream's bar close through a compiled strategy: state updates, sequence stages, then
 * the rules that evaluate on that stream, then the candle-evaluation observer.
 */
internal class AliasCloseEvaluator(
    private val streams: Map<String, HubKey>,
    private val snapshotStore: SnapshotStore,
    private val sequenceRuntime: SequenceRuntime,
    private val warmupGate: WarmupGate,
    private val rules: List<CompiledRule>,
    private val updater: CloseStateUpdater,
    private val ledger: RuleFireLedger,
) {
    var evaluationObserver: (String, HubKey, Candle, Int) -> Unit = { _, _, _, _ -> }

    // Rules grouped by their alias — fireRulesForAlias runs per bar close, and scanning every
    // rule with a string compare to find the alias's few was per-bar overhead.
    val rulesByAlias: Map<String, List<CompiledRule>> by lazy { rules.groupBy { it.ruleAlias } }

    fun evaluate(
        alias: String,
        candle: Candle,
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        updater.updatePerAlias(alias, candle, hub, ctx)
        runSequencesForAlias(alias, candle, hub, ctx)
        val deferSequenceCompletion = fireRulesForAlias(alias, candle, hub, ctx, emit)
        sequenceRuntime.afterRulePass(deferSequenceCompletion)
        evaluationObserver(alias, streams.getValue(alias), candle, rulesByAlias[alias]?.size ?: 0)
    }

    fun runSequencesForAlias(
        alias: String,
        candle: Candle,
        hub: CandleHub,
        ctx: StrategyContext,
    ) {
        val ec =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = alias,
                evaluationTimeMs = candle.endTime,
                sequences = sequenceRuntime,
            )
        sequenceRuntime.onCandle(candle, ec, streamAlias = alias) { aliases -> warmupGate.isWarm(aliases) }
    }

    /**
     * Fire every rule whose `ruleAlias` matches [alias] and whose referenced streams
     * are warm. Must run after [CloseStateUpdater.updatePerAlias] so the rule body sees current state.
     */
    fun fireRulesForAlias(
        alias: String,
        candle: Candle,
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ): Boolean {
        val aliasRules = rulesByAlias[alias] ?: return false
        val ec =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = alias,
                evaluationTimeMs = candle.endTime,
                sequences = sequenceRuntime,
            )
        var consumerFired = false
        var consumerAccepted = false
        for (rule in aliasRules) {
            if (!warmupGate.isWarm(rule.referencedAliases)) continue
            when (ledger.fireAndCommit(rule, ec, ctx, emit)) {
                SequenceFireOutcome.ACCEPTED -> {
                    consumerFired = true
                    consumerAccepted = true
                }
                SequenceFireOutcome.SUPPRESSED -> consumerFired = true
                SequenceFireOutcome.NOT_CONSUMING -> Unit
            }
        }
        sequenceRuntime.persistRuleEdges()
        return consumerFired && !consumerAccepted
    }
}
