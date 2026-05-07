package com.qkt.dsl.compile

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ExprAst
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
        val streamSymbols: Map<String, String> = ast.streams.associate { it.alias to it.symbol }
        val resolver = LetResolver(ast.lets)
        val bindings = IndicatorBinding.Bag()
        val aggregates = AggregateBinding.Bag()
        val exprCompiler = ExprCompiler(bindings, aggregates)
        val actionCompiler = ActionCompiler(exprCompiler)

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
                val streamAlias =
                    when (val a = rule.action) {
                        is Buy -> a.stream
                        is Sell -> a.stream
                        else -> error("Action ${a::class.simpleName} not supported in 11c2")
                    }
                val symbol = streamSymbols[streamAlias] ?: error("Unknown stream alias: $streamAlias")
                val compiledCond = exprCompiler.compile(cond, ruleSymbol = symbol)
                val action = actionCompiler.compile(rule.action)
                val isBuy = rule.action is Buy
                val isSell = rule.action is Sell
                CompiledRule(
                    condition = compiledCond,
                    action = action,
                    ruleSymbol = symbol,
                    isBuy = isBuy,
                    isSell = isSell,
                    onBuyCaptures = plan.captureOnBuy.map { it to letCompiledRhs.getValue(it) },
                    onSellCaptures = plan.captureOnSell.map { it to letCompiledRhs.getValue(it) },
                    onOpenCaptures = plan.captureOnOpen.map { it to letCompiledRhs.getValue(it) },
                )
            }

        return CompiledStrategy(
            streamSymbols = streamSymbols,
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
    private val streamSymbols: Map<String, String>,
    private val bindings: IndicatorBinding.Bag,
    private val aggregates: AggregateBinding.Bag,
    private val snapshotStore: SnapshotStore,
    private val plan: SnapshotPlan,
    private val letCompiledRhs: Map<String, CompiledExpr>,
    private val transitions: PositionTransitions,
    private val rules: List<CompiledRule>,
) : Strategy {
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
        if (candle.symbol !in streamSymbols.values) return

        val ec =
            EvalContext(
                candle = candle,
                streamSymbols = streamSymbols,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
            )

        // 1. Position transitions for this candle's symbol
        for ((_, symbol) in streamSymbols) {
            if (candle.symbol != symbol) continue
            val qty = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
            val transition = transitions.observe(symbol, qty)
            when (transition) {
                PositionTransition.ClosedToZero, PositionTransition.Flipped -> {
                    for (name in plan.captureOnOpen) {
                        snapshotStore.clearSlot(symbol, name, SnapshotOpen)
                    }
                    aggregates.bindingsForSymbol(symbol).forEach { it.resetIfSinceOpen() }
                }
                PositionTransition.OpenedFromZero -> {
                    aggregates.bindingsForSymbol(symbol).forEach { it.resetIfSinceOpen() }
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
            for ((_, symbol) in streamSymbols) {
                if (symbol != candle.symbol) continue
                snapshotStore.pushRolling(symbol, name, if (v is Value.Num) v.v else null)
            }
        }

        // 4. Aggregate updates
        for (b in aggregates.all()) {
            if (b.window is SinceOpen) {
                val curQty = ctx.positions.positionFor(b.ruleSymbol)?.quantity ?: BigDecimal.ZERO
                if (curQty.signum() != 0) b.update(ec)
            } else {
                b.update(ec)
            }
        }

        // 5. Rules
        for (rule in rules) {
            val sig = rule.fire(ec, ctx) ?: continue
            emit(sig)
        }
    }
}
