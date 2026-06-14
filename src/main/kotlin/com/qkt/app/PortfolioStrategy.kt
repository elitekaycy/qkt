package com.qkt.app

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.WhenRun
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.CompiledExpr
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.dsl.compile.Value
import com.qkt.dsl.portfolio.CompiledChild
import com.qkt.dsl.portfolio.PortfolioCompiled
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import org.slf4j.LoggerFactory

/**
 * Strategy implementation that wraps N child strategies and gates their activation per
 * [WhenRun] / [AlwaysRun] rules.
 *
 * v1 simplification: a single strategyId for the whole portfolio (children share PnL / positions).
 * Per-child state drill-down is deferred to v2.
 */
class PortfolioStrategy(
    private val compiled: PortfolioCompiled,
    private val exprCompiler: ExprCompiler,
) : DslCompiledStrategy {
    private val log = LoggerFactory.getLogger(PortfolioStrategy::class.java)

    private val gateRules: List<CompiledRule> =
        compiled.ast.rules.map { rule ->
            when (rule) {
                is WhenRun -> CompiledRule(rule.alias, exprCompiler.compile(rule.cond))
                is AlwaysRun -> CompiledRule(rule.alias, null)
            }
        }

    private var lastActive: Set<String> = emptySet()
    private var lastCandlesByStream: Map<String, Candle> = emptyMap()
    private var lastCtx: StrategyContext? = null

    override val declaredStreams: Map<String, HubKey> =
        compiled.ast.streams.associate { it.alias to HubKey(it.broker, it.symbol, it.timeframe) }

    // Portfolio gate rules cannot themselves carry STACK_AT — only the child strategies
    // do. The runtime accesses each child's pendingStacks via its DslCompiledStrategy.
    override val pendingStacks: PendingStacks = PendingStacks()

    // Aggregate STACK_AT symbols across children so the broker capability check at
    // TradingPipeline.init covers the symbols any child will actually stack on.
    override val multiPositionPerSymbolSymbols: Set<String> =
        compiled.children
            .mapNotNull { it.compiled as? DslCompiledStrategy }
            .flatMap { it.multiPositionPerSymbolSymbols }
            .toSet()

    // Aggregate volume-requiring symbols across children so the feed-capability check at
    // TradingPipeline.init covers any symbol a child binds VWAP/OBV to.
    override val volumeRequiringSymbols: Set<String> =
        compiled.children
            .mapNotNull { it.compiled as? DslCompiledStrategy }
            .flatMap { it.volumeRequiringSymbols }
            .toSet()

    override val retentionByKey: Map<HubKey, Int> =
        declaredStreams.values.associateWith { 1 } +
            compiled.children
                .filterIsInstance<CompiledChild>()
                .mapNotNull { (it.compiled as? DslCompiledStrategy)?.retentionByKey }
                .fold(emptyMap()) { acc, m -> mergeRetention(acc, m) }

    override fun bindToHub(
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        // Bind portfolio-level streams so gate expressions have current candles.
        for ((alias, key) in declaredStreams) {
            hub.onClosed(key, ctx.strategyId) { candle ->
                lastCandlesByStream = lastCandlesByStream + (alias to candle)
            }
        }
        // Bind children. Each child registers its own streams + subscribers.
        for (child in compiled.children) {
            val childStrategy = child.compiled
            if (childStrategy is DslCompiledStrategy) {
                for ((key, retention) in childStrategy.retentionByKey) {
                    hub.register(key, retention, ctx.strategyId)
                }
                childStrategy.bindToHub(hub, ctx, makeChildEmit(child, ctx, emit))
            }
        }
    }

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        lastCtx = ctx
        val newActive = computeActiveChildren(ctx)
        applyTransitions(lastActive, newActive, ctx, emit)
        lastActive = newActive

        for (child in compiled.children) {
            if (child.alias !in newActive) continue
            child.compiled.onTick(tick, ctx, makeChildEmit(child, ctx, emit))
        }
    }

    override fun onCandle(
        candle: Candle,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        for (child in compiled.children) {
            if (child.alias !in lastActive) continue
            child.compiled.onCandle(candle, ctx, makeChildEmit(child, ctx, emit))
        }
    }

    private fun makeChildEmit(
        child: CompiledChild,
        ctx: StrategyContext,
        upstream: (Signal) -> Unit,
    ): (Signal) -> Unit = { sig -> upstream(sig) }

    private fun computeActiveChildren(ctx: StrategyContext): Set<String> {
        val active = mutableSetOf<String>()
        for (rule in gateRules) {
            val isActive =
                rule.gate?.let { gate ->
                    val ec = makeGateEvalContext(ctx) ?: return@let false
                    val v = gate.evaluate(ec)
                    (v as? Value.Bool)?.v ?: false
                } ?: true
            if (isActive) active.add(rule.alias)
        }
        return active
    }

    private fun makeGateEvalContext(ctx: StrategyContext): EvalContext? {
        val candle = lastCandlesByStream.values.firstOrNull() ?: return null
        return EvalContext(
            candle = candle,
            streams = declaredStreams,
            lets = emptyMap(),
            strategyContext = ctx,
        )
    }

    private fun applyTransitions(
        prev: Set<String>,
        curr: Set<String>,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        for (alias in curr - prev) {
            log.info("portfolio activate portfolio={} child={}", compiled.ast.name, alias)
        }
        for (alias in prev - curr) {
            val child = compiled.children.firstOrNull { it.alias == alias } ?: continue
            log.info(
                "portfolio deactivate portfolio={} child={} hold={}",
                compiled.ast.name,
                alias,
                child.hold,
            )
            for (symbol in child.symbols) {
                emit(Signal.CancelPendingForSymbol(symbol))
                if (!child.hold) {
                    val pos = ctx.positions.positionFor(symbol)
                    val qty = pos?.quantity
                    if (qty != null) {
                        when {
                            qty.signum() > 0 -> emit(Signal.Sell(symbol, qty))
                            qty.signum() < 0 -> emit(Signal.Buy(symbol, qty.abs()))
                        }
                    }
                }
            }
        }
    }

    private data class CompiledRule(
        val alias: String,
        val gate: CompiledExpr?,
    )

    private fun mergeRetention(
        a: Map<HubKey, Int>,
        b: Map<HubKey, Int>,
    ): Map<HubKey, Int> {
        val out = a.toMutableMap()
        for ((k, v) in b) out[k] = maxOf(out[k] ?: 0, v)
        return out
    }
}
