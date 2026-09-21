package com.qkt.dsl.compile

import com.qkt.dsl.ast.SyncGroupDecl
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream

/**
 * The runtime [DslCompiledStrategy] that [AstCompiler] produces. It owns the compiled rules and
 * derived state and delegates each concern to one collaborator: bar-close state updates
 * ([CloseStateUpdater]), per-stream evaluation ([AliasCloseEvaluator]), hub wiring
 * ([StrategyHubWiring]), rule/order attribution ([RuleFireLedger]), schedules and exit hooks.
 */
internal class CompiledStrategy(
    strategyFingerprint: String,
    private val ids: com.qkt.common.SequentialIdGenerator,
    private val streams: Map<String, HubKey>,
    override val retentionByKey: Map<HubKey, Int>,
    bindings: IndicatorBinding.Bag,
    aggregates: AggregateBinding.Bag,
    private val snapshotStore: SnapshotStore,
    plan: SnapshotPlan,
    letCompiledRhs: Map<String, CompiledExpr>,
    transitions: PositionTransitions,
    private val rules: List<CompiledRule>,
    override val pendingStacks: PendingStacks,
    override val multiPositionPerSymbolSymbols: Set<String>,
    override val volumeRequiringSymbols: Set<String>,
    override val usesBookSizing: Boolean,
    private val metaRefs: List<MetaRef>,
    private val warmupGate: WarmupGate,
    override val perStreamWarmup: Map<WarmupStream, WarmupSpec>,
    syncGroups: List<SyncGroupDecl>,
    schedules: List<CompiledSchedule>,
    override val quoteFieldStreams: Set<String>,
    baskets: List<com.qkt.dsl.ast.BasketDecl>,
    private val sequenceRuntime: SequenceRuntime,
    private val exitHookCatalog: ExitHookCatalog,
) : DslCompiledStrategy,
    com.qkt.strategy.PerStreamWarmable {
    private val subscribedSymbols: Set<String> = streams.values.map { it.qktSymbol }.toSet()
    private val binding = StrategyHubBinding(streams)
    private val ledger = RuleFireLedger(strategyFingerprint, streams)
    private val updater =
        CloseStateUpdater(streams, warmupGate, transitions, bindings, plan, letCompiledRhs, snapshotStore, aggregates)
    private val evaluator =
        AliasCloseEvaluator(streams, snapshotStore, sequenceRuntime, warmupGate, rules, updater, ledger)
    private val wiring = StrategyHubWiring(streams, syncGroups, baskets, sequenceRuntime, updater, evaluator)
    private val scheduleFirer = ScheduleFirer(schedules, streams, snapshotStore, sequenceRuntime, binding)
    private val exitHooks = ExitHookExecutor(exitHookCatalog, streams, snapshotStore, sequenceRuntime, binding)
    private val tickFed = TickFedIndicators(streams, bindings)

    override val declaredStreams: Map<String, HubKey> get() = streams

    override fun resumeOrderIds(usedIds: Collection<String>) = ids.resumePast(usedIds)

    override fun observeCandleEvaluations(
        observer: (alias: String, key: HubKey, candle: Candle, rulesEvaluated: Int) -> Unit,
    ) {
        check(!binding.hubBound) { "Candle evaluation observer must be bound before the strategy hub" }
        evaluator.evaluationObserver = observer
    }

    override fun observeRuleDecisions(observer: (RuleDecisionAudit) -> Unit) {
        check(!binding.hubBound) { "Rule decision observer must be bound before the strategy hub" }
        ledger.ruleDecisionObserver = observer
    }

    override fun exitHookReferences(): Map<String, ExitHookRef> = exitHookCatalog.references()

    override fun executeExitHook(
        ref: ExitHookRef,
        exit: ExitContext,
        timestampMs: Long,
    ): List<Signal> = exitHooks.execute(ref, exit, timestampMs)

    override fun bindStatePersistor(
        strategyId: String,
        persistor: com.qkt.persistence.StatePersistor,
    ) {
        sequenceRuntime.bindPersistor(strategyId, persistor)
    }

    override fun clearRuleEdges() {
        ledger.clear()
        sequenceRuntime.clearRuleEdges()
    }

    override fun onPositionOpened(symbol: String) {
        rules.forEach { it.onPositionOpened(symbol) }
        sequenceRuntime.persistRuleEdges()
    }

    override fun onOrderRejected(clientOrderId: String) {
        ledger.onOrderRejected(clientOrderId)
        sequenceRuntime.persistRuleEdges()
    }

    override fun onOrderSubmitted(
        signal: Signal,
        clientOrderId: String,
    ): DecisionOrderLink? = ledger.onOrderSubmitted(signal, clientOrderId)

    override fun onOrderTerminal(clientOrderId: String) {
        ledger.onOrderTerminal(clientOrderId)
    }

    override fun bindSchedules(
        runner: ScheduleRunner,
        ctx: StrategyContext,
        nowMs: Long,
        emit: (Signal) -> Unit,
    ) {
        scheduleFirer.bind(runner, ctx, nowMs, emit)
    }

    override fun bindToHub(
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        check(!binding.hubBound) { "CompiledStrategy already bound to a hub" }
        validateMetaRefs(metaRefs, ctx)
        binding.bind(hub, ctx)
        wiring.wire(hub, ctx, emit)
    }

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        tickFed.onTick(tick)
    }

    override fun onCandle(
        candle: Candle,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        if (binding.hubBound) return
        if (candle.symbol !in subscribedSymbols) return

        for ((alias, key) in streams) {
            if (key.qktSymbol == candle.symbol) warmupGate.onClosedCandle(alias)
        }

        val ec =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                evaluationTimeMs = candle.endTime,
                sequences = sequenceRuntime,
            )

        // 1-4. Position transitions, indicators, rolling snapshots, aggregates
        updater.updateForCandle(candle, ec, ctx)

        // 5. Sequence state machines
        sequenceRuntime.onCandle(candle, ec) { aliases -> warmupGate.isWarm(aliases) }

        // 6. Rules
        var consumerFired = false
        var consumerAccepted = false
        for (rule in rules) {
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
        sequenceRuntime.afterRulePass(consumerFired && !consumerAccepted)
    }
}
