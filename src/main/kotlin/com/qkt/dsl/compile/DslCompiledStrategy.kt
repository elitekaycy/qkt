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

    /**
     * Symbols on which this strategy will create more than one concurrent leg via
     * `STACK_AT`. The runtime verifies that the routing broker for each such symbol
     * declares [com.qkt.broker.OrderTypeCapability.MULTI_POSITION_PER_SYMBOL] before
     * the strategy goes live. Empty for strategies with no `STACK_AT` clauses.
     */
    val multiPositionPerSymbolSymbols: Set<String>
        get() = emptySet()

    /**
     * Symbols bound to a volume-weighted indicator (VWAP/OBV). The runtime verifies the data
     * source for each declares [com.qkt.marketdata.source.MarketSourceCapability.VOLUME] before the
     * strategy goes live — a volume indicator on a quote-only feed (MT5 FX/metals) otherwise never
     * becomes ready and the strategy silently never fires. Empty when no such indicator is used.
     */
    val volumeRequiringSymbols: Set<String>
        get() = emptySet()

    /**
     * Stream aliases whose conditions read quote fields (`bid`/`ask`/`spread`). These
     * evaluate Undefined unless the data source carries real quotes — bar-synthesized
     * backtest feeds do not, so spread-aware rules silently never fire there. The
     * backtest engine warns loudly when this is non-empty.
     */
    val quoteFieldStreams: Set<String>
        get() = emptySet()

    /** Subscribes this strategy to the shared [CandleHub] for hub-driven dispatch. */
    fun bindToHub(
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    )

    /**
     * Register every `SCHEDULE` clause this strategy declared with [runner]. Called
     * after [bindToHub]. Strategies with no `SCHEDULE` block have nothing to register
     * and the default implementation is a no-op (#77).
     *
     * [nowMs] is the engine clock at registration time — used by the runner as the
     * watermark for "first eligible fire." Pass the same clock the rest of the
     * pipeline reads from so backtest and live behave identically.
     */
    fun bindSchedules(
        runner: ScheduleRunner,
        ctx: StrategyContext,
        nowMs: Long,
        emit: (Signal) -> Unit,
    ) {
        // default no-op
    }
}
