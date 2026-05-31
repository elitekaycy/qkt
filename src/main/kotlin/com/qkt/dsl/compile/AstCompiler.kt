package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.SyncGroupDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal

class AstCompiler {
    fun compile(ast: StrategyAst): Strategy {
        val streams: Map<String, HubKey> =
            ast.streams.associate { it.alias to HubKey(it.broker, it.symbol, it.timeframe) }
        val resolver = LetResolver(ast.lets)
        val bindings = IndicatorBinding.Bag()
        val aggregates = AggregateBinding.Bag()
        val exprCompiler = ExprCompiler(bindings, aggregates)
        val strategyLogger = org.slf4j.LoggerFactory.getLogger("com.qkt.dsl.strategy.${ast.name}")
        val ids = com.qkt.common.SequentialIdGenerator(prefix = "dsl-${ast.name}-")
        val pendingStacks = PendingStacks()
        val actionCompiler = ActionCompiler(exprCompiler, strategyLogger, ids, pendingStacks)

        val whenThens: List<WhenThen> =
            ast.rules.map {
                require(it is WhenThen) { "Only WHEN-THEN rules are supported" }
                it
            }
        val resolvedConditions: List<ExprAst> = whenThens.map { resolver.resolve(it.cond) }
        val plan = SnapshotPlan.scan(resolvedConditions)

        val maxRollingPerName: Map<String, Int> = plan.rollingMaxN
        val snapshotStore = SnapshotStore(maxRollingPerName)

        val letRhsByName: Map<String, ExprAst> = ast.lets.associate { it.name to resolver.resolve(it.expr) }

        val capturableNames: Set<String> =
            (plan.captureOnBuy + plan.captureOnSell + plan.captureOnOpen + plan.rollingMaxN.keys).toSet()
        val letCompiledRhs: Map<String, CompiledExpr> =
            capturableNames.associateWith { name ->
                val rhs = letRhsByName[name] ?: error("Snapshot/rolling LET '$name' not declared")
                exprCompiler.compile(rhs)
            }

        val rules: List<CompiledRule> =
            whenThens.zip(resolvedConditions).map { (rule, cond) ->
                val primary: ActionAst =
                    when (val a = rule.action) {
                        is Block -> a.actions.firstOrNull { it !is Log } ?: a.actions.first()
                        is OcoEntry -> a.leg1
                        else -> a
                    }
                val streamAlias: String? =
                    when (primary) {
                        is Buy -> primary.stream
                        is Sell -> primary.stream
                        is Close -> primary.stream
                        is Cancel -> primary.stream
                        is CloseAll, is CancelAll, is Log -> null
                        else -> null
                    }
                val ruleAlias =
                    streamAlias
                        ?: streams.keys.firstOrNull()
                        ?: error("Strategy must declare at least one stream")
                val ruleSymbol =
                    streams[ruleAlias]?.qktSymbol
                        ?: error("Unknown stream alias: $ruleAlias")
                val compiledCond = exprCompiler.compile(cond, ruleAlias = ruleAlias)
                val mergedAction = mergeDefaults(rule.action, ast.defaults)
                val action = actionCompiler.compile(mergedAction)
                val isBuy = primary is Buy
                val isSell = primary is Sell
                val referencedAliases = collectStreamAliases(rule)
                CompiledRule(
                    condition = compiledCond,
                    action = action,
                    ruleAlias = ruleAlias,
                    ruleSymbol = ruleSymbol,
                    isBuy = isBuy,
                    isSell = isSell,
                    onBuyCaptures = plan.captureOnBuy.map { it to letCompiledRhs.getValue(it) },
                    onSellCaptures = plan.captureOnSell.map { it to letCompiledRhs.getValue(it) },
                    onOpenCaptures = plan.captureOnOpen.map { it to letCompiledRhs.getValue(it) },
                    referencedAliases = referencedAliases,
                )
            }

        val maxRolling = plan.rollingMaxN.values.maxOrNull() ?: 0
        val retention = maxOf(1, maxRolling + 1)
        val retentionByKey: Map<HubKey, Int> =
            streams.values.associateWith { retention }

        val stackAtSymbols: Set<String> =
            whenThens
                .flatMap { collectStackAtSymbols(it.action, streams) }
                .toSet()

        val metaRefs = collectMetaRefs(ast, streams)

        val perStreamWarmup: Map<String, Int> = WarmupRequirements.compute(ast)
        val warmupGate = WarmupGate(perStreamWarmup)

        val perStreamWarmupSpec: Map<String, com.qkt.strategy.WarmupSpec> =
            perStreamWarmup
                .mapNotNull { (alias, bars) ->
                    val key = streams[alias] ?: return@mapNotNull null
                    val window =
                        com.qkt.candles.TimeWindow
                            .parse(key.timeframe)
                    key.qktSymbol to
                        com.qkt.strategy.WarmupSpec
                            .Bars(window, bars)
                }.toMap()

        val compiledSchedules: List<CompiledSchedule> =
            ast.schedules.map { decl ->
                CompiledSchedule(
                    decl = decl,
                    action = actionCompiler.compile(mergeDefaults(decl.action, ast.defaults)),
                )
            }

        return CompiledStrategy(
            streams = streams,
            retentionByKey = retentionByKey,
            bindings = bindings,
            aggregates = aggregates,
            snapshotStore = snapshotStore,
            plan = plan,
            letCompiledRhs = letCompiledRhs,
            transitions = PositionTransitions(),
            rules = rules,
            pendingStacks = pendingStacks,
            multiPositionPerSymbolSymbols = stackAtSymbols,
            metaRefs = metaRefs,
            warmupGate = warmupGate,
            perStreamWarmup = perStreamWarmupSpec,
            syncGroups = ast.syncGroups,
            schedules = compiledSchedules,
        )
    }

    private fun collectStackAtSymbols(
        action: ActionAst,
        streams: Map<String, HubKey>,
    ): Set<String> {
        val out = mutableSetOf<String>()

        fun walk(a: ActionAst) {
            when (a) {
                is Buy ->
                    if (a.opts.stackAts.isNotEmpty()) {
                        streams[a.stream]?.qktSymbol?.let { out.add(it) }
                    }
                is Sell ->
                    if (a.opts.stackAts.isNotEmpty()) {
                        streams[a.stream]?.qktSymbol?.let { out.add(it) }
                    }
                is Block -> a.actions.forEach { walk(it) }
                is OcoEntry -> {
                    walk(a.leg1)
                    walk(a.leg2)
                }
                else -> {} // other actions don't carry stackAts
            }
        }
        walk(action)
        return out
    }
}

private class CompiledStrategy(
    private val streams: Map<String, HubKey>,
    override val retentionByKey: Map<HubKey, Int>,
    private val bindings: IndicatorBinding.Bag,
    private val aggregates: AggregateBinding.Bag,
    private val snapshotStore: SnapshotStore,
    private val plan: SnapshotPlan,
    private val letCompiledRhs: Map<String, CompiledExpr>,
    private val transitions: PositionTransitions,
    private val rules: List<CompiledRule>,
    override val pendingStacks: PendingStacks,
    override val multiPositionPerSymbolSymbols: Set<String>,
    private val metaRefs: List<MetaRef>,
    private val warmupGate: WarmupGate,
    override val perStreamWarmup: Map<String, com.qkt.strategy.WarmupSpec>,
    private val syncGroups: List<SyncGroupDecl>,
    private val schedules: List<CompiledSchedule>,
) : DslCompiledStrategy,
    com.qkt.strategy.PerStreamWarmable {
    private val subscribedSymbols: Set<String> = streams.values.map { it.qktSymbol }.toSet()
    private var hubBound: Boolean = false
    private var boundHub: CandleHub? = null

    override val declaredStreams: Map<String, HubKey> get() = streams

    override fun bindSchedules(
        runner: ScheduleRunner,
        ctx: StrategyContext,
        nowMs: Long,
        emit: (Signal) -> Unit,
    ) {
        for (sched in schedules) {
            runner.register(
                strategyId = ctx.strategyId,
                schedule = sched.decl,
                emit = { fireSchedule(sched, ctx, emit) },
                nowMs = nowMs,
            )
        }
    }

    private fun fireSchedule(
        sched: CompiledSchedule,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        val hub = boundHub ?: return
        val syntheticCandle = latestKnownCandle(hub) ?: run {
            scheduleLog.warn(
                "schedule fire skipped for strategy={} — no stream has a closed bar yet " +
                    "(warmup not complete). Trigger will retry on the next fire time.",
                ctx.strategyId,
            )
            return
        }
        val ec =
            EvalContext(
                candle = syntheticCandle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = null,
            )
        for (sig in sched.action(ec)) emit(sig)
    }

    private fun latestKnownCandle(hub: CandleHub): Candle? {
        for ((_, key) in streams) {
            val c = hub.latest(key)
            if (c != null) return c
        }
        return null
    }

    private companion object {
        private val scheduleLog =
            org.slf4j.LoggerFactory.getLogger("com.qkt.dsl.compile.ScheduleFire")
    }

    override fun bindToHub(
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        check(!hubBound) { "CompiledStrategy already bound to a hub" }
        validateMetaRefs(ctx)
        hubBound = true
        boundHub = hub

        // Aliases that belong to ANY sync group are evaluated via the sync callback
        // instead of the per-stream close. Without this split, both gold and silver
        // would individually fire their rules with cross-stream data from the wrong
        // window. (#45 Phase 35.)
        val syncedAliases: Set<String> = syncGroups.flatMap { it.aliases }.toSet()

        for ((alias, key) in streams) {
            // Phase 25B: credit the gate with whatever historical bars the seed phase
            // (run by LiveSession before bindToHub) placed in the hub. Without this,
            // the gate stays cold even when lookback + indicators are already warm.
            val seeded = hub.historySize(key)
            if (seeded > 0) warmupGate.recordBars(alias, seeded)
            if (alias in syncedAliases) continue
            hub.onClosed(key, ctx.strategyId) { closed ->
                evaluate(alias, closed, hub, ctx, emit)
            }
        }

        for (group in syncGroups) {
            val members = group.aliases.associateWith { streams.getValue(it) }
            val groupKey = SyncGroupKey(members = members, timeoutMs = group.timeoutMs)
            hub.registerSyncGroup(groupKey, ctx.strategyId)
            hub.onSyncClosed(groupKey, ctx.strategyId) { bars ->
                // Two-pass: every alias's indicators/snapshots/aggregates update FIRST,
                // then rules fire. This ensures a gold-anchored rule that reads
                // `sma(silver.close, N)` sees silver's same-window value, not the
                // previous window's. Without this split, the alias evaluated first
                // would fire rules against the other alias's stale indicator state.
                for (alias in group.aliases) {
                    updatePerAlias(alias, bars.getValue(alias), hub, ctx)
                }
                for (alias in group.aliases) {
                    fireRulesForAlias(alias, bars.getValue(alias), hub, ctx, emit)
                }
            }
        }
    }

    private fun validateMetaRefs(ctx: StrategyContext) {
        val registry = ctx.instruments
        val missing = metaRefs.firstOrNull { registry.lookup(it.qktSymbol) == null }
        if (missing != null) {
            error(
                "Strategy '${ctx.strategyId}' references '${missing.stream}.${missing.field}' " +
                    "but no InstrumentMeta is registered for ${missing.qktSymbol}. " +
                    "Populate it via the MT5 broker connection (live) or a YAML manifest " +
                    "in qkt.config.yaml (backtest).",
            )
        }
    }

    private fun evaluate(
        alias: String,
        candle: Candle,
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        updatePerAlias(alias, candle, hub, ctx)
        fireRulesForAlias(alias, candle, hub, ctx, emit)
    }

    /**
     * Per-alias close updates: warmup gate, position transitions, indicator updates,
     * rolling snapshot capture, aggregate updates. No rule firing — see
     * [fireRulesForAlias]. Split out so a sync group can run this for every member
     * before any rule fires on any member (#45).
     */
    private fun updatePerAlias(
        alias: String,
        candle: Candle,
        hub: CandleHub,
        ctx: StrategyContext,
    ) {
        warmupGate.onClosedCandle(alias)

        val ec =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = alias,
            )

        val symbol = streams[alias]!!.qktSymbol

        val qty = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
        val transition = transitions.observe(symbol, qty)
        when (transition) {
            PositionTransition.ClosedToZero, PositionTransition.Flipped -> {
                for (name in plan.captureOnOpen) snapshotStore.clearSlot(alias, name, SnapshotOpen)
                aggregates.bindingsForAlias(alias).forEach { it.resetIfSinceOpen() }
            }
            PositionTransition.OpenedFromZero ->
                aggregates.bindingsForAlias(alias).forEach { it.resetIfSinceOpen() }
            PositionTransition.Stay -> {}
        }

        bindings.updateForAlias(alias, ec)

        for ((name, _) in plan.rollingMaxN) {
            val rhs = letCompiledRhs[name] ?: continue
            val v = rhs.evaluate(ec)
            snapshotStore.pushRolling(alias, name, if (v is Value.Num) v.v else null)
        }

        for (b in aggregates.bindingsForAlias(alias)) {
            if (b.window is SinceOpen) {
                val curQty = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
                if (curQty.signum() != 0) b.update(ec)
            } else {
                b.update(ec)
            }
        }
    }

    /**
     * Fire every rule whose `ruleAlias` matches [alias] and whose referenced streams
     * are warm. Must run after [updatePerAlias] so the rule body sees current state.
     */
    private fun fireRulesForAlias(
        alias: String,
        candle: Candle,
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
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
            )
        for (rule in rules) {
            if (rule.ruleAlias != alias) continue
            if (!warmupGate.isWarm(rule.referencedAliases)) continue
            for (sig in rule.fire(ec, ctx)) emit(sig)
        }
    }

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        // Phase 25E: feed tick-fed indicators (e.g. VWAP) on every raw tick.
        // Candle-fed indicators keep updating only at candle close in [evaluate]; the
        // two paths are disjoint by indicator input kind, so there's no double-feeding.
        for ((alias, key) in streams) {
            if (key.qktSymbol != tick.symbol) continue
            val tickBindings = bindings.tickFedForAlias(alias)
            if (tickBindings.isEmpty()) continue
            for (b in tickBindings) b.updateFromTick(tick)
        }
    }

    override fun onCandle(
        candle: Candle,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        if (hubBound) return
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
            )

        // 1. Position transitions for this candle's symbol
        for ((alias, key) in streams) {
            val symbol = key.qktSymbol
            if (candle.symbol != symbol) continue
            val qty = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
            val transition = transitions.observe(symbol, qty)
            when (transition) {
                PositionTransition.ClosedToZero, PositionTransition.Flipped -> {
                    for (name in plan.captureOnOpen) {
                        snapshotStore.clearSlot(alias, name, SnapshotOpen)
                    }
                    aggregates.bindingsForAlias(alias).forEach { it.resetIfSinceOpen() }
                }
                PositionTransition.OpenedFromZero -> {
                    aggregates.bindingsForAlias(alias).forEach { it.resetIfSinceOpen() }
                }
                PositionTransition.Stay -> {}
            }
        }

        // 2. Indicators
        bindings.updateAll(ec)

        // 3. Per-candle rolling snapshot capture
        for ((name, _) in plan.rollingMaxN) {
            val rhs = letCompiledRhs[name] ?: continue
            val v = rhs.evaluate(ec)
            for ((alias, key) in streams) {
                if (key.qktSymbol != candle.symbol) continue
                snapshotStore.pushRolling(alias, name, if (v is Value.Num) v.v else null)
            }
        }

        // 4. Aggregate updates
        for (b in aggregates.all()) {
            if (b.window is SinceOpen) {
                val symbol = streams[b.ruleAlias]?.qktSymbol
                val curQty = symbol?.let { ctx.positions.positionFor(it)?.quantity } ?: BigDecimal.ZERO
                if (curQty.signum() != 0) b.update(ec)
            } else {
                b.update(ec)
            }
        }

        // 5. Rules
        for (rule in rules) {
            if (!warmupGate.isWarm(rule.referencedAliases)) continue
            for (sig in rule.fire(ec, ctx)) emit(sig)
        }
    }
}
