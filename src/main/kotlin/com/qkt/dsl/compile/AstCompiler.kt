package com.qkt.dsl.compile

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.StrategyAst
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
        val actionCompiler = ActionCompiler(exprCompiler, strategyLogger, ids)

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
                val streamAlias: String? =
                    when (val a = rule.action) {
                        is Buy -> a.stream
                        is Sell -> a.stream
                        is Close -> a.stream
                        is Cancel -> a.stream
                        is CloseAll, is CancelAll, is Log -> null
                    }
                val ruleAlias =
                    streamAlias
                        ?: streams.keys.firstOrNull()
                        ?: error("Strategy must declare at least one stream")
                val ruleSymbol =
                    streams[ruleAlias]?.symbol
                        ?: error("Unknown stream alias: $ruleAlias")
                val compiledCond = exprCompiler.compile(cond, ruleAlias = ruleAlias)
                val mergedAction = mergeDefaults(rule.action, ast.defaults)
                val action = actionCompiler.compile(mergedAction)
                val isBuy = mergedAction is Buy
                val isSell = mergedAction is Sell
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
                )
            }

        val maxRolling = plan.rollingMaxN.values.maxOrNull() ?: 0
        val retention = maxOf(1, maxRolling + 1)
        val retentionByKey: Map<HubKey, Int> =
            streams.values.associateWith { retention }

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
        )
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
) : DslCompiledStrategy {
    private val subscribedSymbols: Set<String> = streams.values.map { it.symbol }.toSet()
    private var hubBound: Boolean = false
    private var boundHub: CandleHub? = null

    override val declaredStreams: Map<String, HubKey> get() = streams

    override fun bindToHub(
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        check(!hubBound) { "CompiledStrategy already bound to a hub" }
        hubBound = true
        boundHub = hub
        for ((alias, key) in streams) {
            hub.onClosed(key) { closed ->
                evaluate(alias, closed, hub, ctx, emit)
            }
        }
    }

    private fun evaluate(
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

        val symbol = streams[alias]!!.symbol

        // 1. Position transitions for the alias's symbol
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

        // 2. Indicators bound to this alias
        bindings.updateForAlias(alias, ec)

        // 3. Per-candle rolling snapshot capture for this alias
        for ((name, _) in plan.rollingMaxN) {
            val rhs = letCompiledRhs[name] ?: continue
            val v = rhs.evaluate(ec)
            snapshotStore.pushRolling(alias, name, if (v is Value.Num) v.v else null)
        }

        // 4. Aggregate updates for bindings on this alias
        for (b in aggregates.bindingsForAlias(alias)) {
            if (b.window is SinceOpen) {
                val curQty = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
                if (curQty.signum() != 0) b.update(ec)
            } else {
                b.update(ec)
            }
        }

        // 5. Rules whose ruleAlias matches
        for (rule in rules) {
            if (rule.ruleAlias != alias) continue
            for (sig in rule.fire(ec, ctx)) emit(sig)
        }
    }

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
    }

    override fun onCandle(
        candle: Candle,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        if (hubBound) return
        if (candle.symbol !in subscribedSymbols) return

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
            val symbol = key.symbol
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
                if (key.symbol != candle.symbol) continue
                snapshotStore.pushRolling(alias, name, if (v is Value.Num) v.v else null)
            }
        }

        // 4. Aggregate updates
        for (b in aggregates.all()) {
            if (b.window is SinceOpen) {
                val symbol = streams[b.ruleAlias]?.symbol
                val curQty = symbol?.let { ctx.positions.positionFor(it)?.quantity } ?: BigDecimal.ZERO
                if (curQty.signum() != 0) b.update(ec)
            } else {
                b.update(ec)
            }
        }

        // 5. Rules
        for (rule in rules) {
            for (sig in rule.fire(ec, ctx)) emit(sig)
        }
    }
}
