package com.qkt.dsl.compile

import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.marketdata.Candle
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal

/**
 * Updates a compiled strategy's derived state on a bar close before any rule fires: warm-up
 * counts, position transitions, indicators, rolling snapshots and aggregates.
 */
internal class CloseStateUpdater(
    private val streams: Map<String, HubKey>,
    private val warmupGate: WarmupGate,
    private val transitions: PositionTransitions,
    private val bindings: IndicatorBinding.Bag,
    private val plan: SnapshotPlan,
    private val letCompiledRhs: Map<String, CompiledExpr>,
    private val snapshotStore: SnapshotStore,
    private val aggregates: AggregateBinding.Bag,
) {
    /**
     * Per-alias close updates: warmup gate, position transitions, indicator updates,
     * rolling snapshot capture, aggregate updates. No rule firing — see
     * [fireRulesForAlias]. Split out so a sync group can run this for every member
     * before any rule fires on any member (#45).
     */
    fun updatePerAlias(
        alias: String,
        candle: Candle,
        hub: CandleHub,
        ctx: StrategyContext,
        warmupReplay: Boolean = false,
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
                evaluationTimeMs = candle.endTime,
                historyAsOfMs = candle.endTime.takeIf { warmupReplay },
            )

        val symbol = streams[alias]!!.qktSymbol

        if (!warmupReplay) {
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
        }

        bindings.updateForAlias(alias, ec)

        for ((name, _) in plan.rollingMaxN) {
            val rhs = letCompiledRhs[name] ?: continue
            val v = rhs.evaluate(ec)
            snapshotStore.pushRolling(alias, name, if (v is Value.Num) v.v else null)
        }

        for (b in aggregates.bindingsForAlias(alias)) {
            if (b.window is SinceOpen) {
                if (warmupReplay) continue
                val curQty = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
                if (curQty.signum() != 0) b.update(ec)
            } else {
                b.update(ec)
            }
        }
    }

    /**
     * Steps 1-4 of the hub-less [DslCompiledStrategy.onCandle] path: position transitions,
     * indicators, rolling snapshots and aggregates for [candle]'s symbol.
     */
    fun updateForCandle(
        candle: Candle,
        ec: EvalContext,
        ctx: StrategyContext,
    ) {
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
    }
}
