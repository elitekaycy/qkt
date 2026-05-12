package com.qkt.dsl.compile

import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext

/**
 * Marker for strategies produced by the qkt DSL parser/compiler.
 *
 * The pipeline detects this interface and binds DSL strategies via [bindToHub] instead
 * of subscribing them to raw tick/candle events — that's what gives the DSL its
 * multi-stream, multi-timeframe semantics. Hand-written strategies don't implement this
 * and use the default [Strategy.onTick] path.
 */
interface DslCompiledStrategy : Strategy {
    /** Stream alias → underlying `(broker, symbol, timeframe)` declared in the strategy file. */
    val declaredStreams: Map<String, HubKey>

    /** Per-key history retention required by the strategy's indicators. */
    val retentionByKey: Map<HubKey, Int>

    /**
     * Per-strategy stack registry. The action compiler populates this when a
     * `STACK_AT`-bearing BUY/SELL emits its primary submit; the runtime consumes it on
     * the matching [com.qkt.events.BrokerEvent.OrderFilled]. Strategies with no
     * `STACK_AT` clauses have an empty registry that's never written to.
     */
    val pendingStacks: PendingStacks

    /** Subscribes this strategy to the shared [CandleHub] for hub-driven dispatch. */
    fun bindToHub(
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    )
}
