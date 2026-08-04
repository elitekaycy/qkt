package com.qkt.dsl.portfolio

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.WhenRun
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.CompiledExpr
import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.IndicatorBinding
import com.qkt.dsl.compile.SnapshotPlan
import com.qkt.dsl.compile.SnapshotStore
import com.qkt.dsl.compile.Value
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.risk.NoOpRiskView
import com.qkt.strategy.Mode
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal

/**
 * Indicator-aware evaluator for a portfolio's `ALWAYS_RUN` and `WHEN` rules.
 *
 * The gate compiles each `WHEN` condition with full indicator binding, maintains its own
 * [CandleHub] for cross-stream reads, and produces a deterministic [GateState] on every
 * closed candle. The same class runs in live (fed by the supervisor's market source) and
 * in backtest (fed by engine [CandleEvent]s), which is the foundation of backtest=live
 * parity for regime-aware portfolios.
 *
 * Limitations of the current implementation (fail-closed where noted):
 * - History snapshots (`foo.close[N]`) inside portfolio `WHEN` rules are not yet supported.
 * - Tick-fed indicators (e.g. `VWAP` on `.tick`) are not yet supported inside portfolio rules.
 */
class PortfolioGate(
    private val ast: PortfolioAst,
    private val clock: Clock,
    private val calendar: com.qkt.common.TradingCalendar,
) {
    private val hub = CandleHub()
    private val bindingBag = IndicatorBinding.Bag()
    private lateinit var snapshotStore: SnapshotStore
    private lateinit var whenRules: List<Pair<WhenRun, CompiledExpr>>
    private lateinit var streamMap: Map<String, HubKey>
    private lateinit var aliasBySymbol: Map<String, String>
    private lateinit var strategyContext: StrategyContext

    @Volatile
    private var lastState: GateState = GateState.empty()

    /**
     * One-time setup: register portfolio streams on the internal hub, bind indicators from
     * every `WHEN` rule, and compile the rule conditions. Must be called before any ticks
     * or candles are processed.
     */
    fun prepare() {
        streamMap = ast.streams.associate { it.alias to HubKey(it.broker, it.symbol, it.timeframe) }
        aliasBySymbol = streamMap.entries.associate { it.value.qktSymbol to it.key }

        val conditions = ast.rules.filterIsInstance<WhenRun>().map { it.cond }
        val plan = SnapshotPlan.scan(conditions)
        require(plan.rollingMaxN.isEmpty()) {
            "Portfolio WHEN rules with history snapshots (e.g. btc.close[5]) are not yet supported"
        }
        snapshotStore = SnapshotStore(emptyMap())

        // Minimal context for evaluating rule conditions. Position/PnL refs are not expected
        // in portfolio-level rules; they resolve to empty/no-op views.
        strategyContext =
            StrategyContext(
                strategyId = ast.name,
                mode = Mode.LIVE,
                clock = clock,
                calendar = calendar,
                source = EmptySource,
                positions = EmptyPositions,
                pnl = EmptyPnL,
                risk = NoOpRiskView(),
            )

        val compiler = ExprCompiler(bindingBag)
        whenRules =
            ast.rules.filterIsInstance<WhenRun>().map { rule ->
                rule to compiler.compile(rule.cond, ruleAlias = null)
            }

        // Register streams with retention large enough for indicator warmup and any hub lookups.
        // The hub is only used for cross-stream reads; indicators maintain their own internal state.
        val retention = (bindingBag.maxWarmupBars + 1).coerceAtLeast(DEFAULT_RETENTION)
        for ((_, key) in streamMap) {
            hub.register(key, retention, strategyId = GATE_OWNER)
        }
    }

    /** Current gate state; safe to call from any thread. */
    fun currentState(): GateState = lastState

    /**
     * Initial state for use before any candle has arrived. Activates `ALWAYS_RUN` children;
     * conditional children start inactive.
     */
    fun initialState(): GateState {
        val desired = mutableMapOf<String, Boolean>()
        for (rule in ast.rules) {
            if (rule is AlwaysRun) desired[rule.alias] = true
        }
        val state = GateState(activeByAlias = desired, weightByAlias = emptyMap(), regimeName = null, changed = true)
        lastState = state
        return state
    }

    /**
     * Process one closed candle. In live this is driven by the supervisor's aggregator; in
     * backtest it is driven by the engine's [CandleEvent] stream.
     */
    fun onCandle(candle: Candle) {
        val alias = aliasBySymbol[candle.symbol] ?: return
        val key = streamMap[alias] ?: return

        // Make the closed candle visible to cross-stream expressions (e.g. gold.close in a
        // rule evaluated on a silver candle).
        hub.publish(key, candle)

        val ctx =
            EvalContext(
                candle = candle,
                streams = streamMap,
                lets = emptyMap(),
                strategyContext = strategyContext,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = alias,
                evaluationTimeMs = candle.endTime,
            )

        // Advance only the bindings whose primary stream matches the closing candle. This is
        // cheaper than updateAll(ctx) and is the same per-alias dispatch used by strategies.
        bindingBag.updateForAlias(alias, ctx)

        val desired = mutableMapOf<String, Boolean>()
        for (rule in ast.rules) {
            if (rule is AlwaysRun) desired[rule.alias] = true
        }
        for ((rule, compiled) in whenRules) {
            val result = compiled.evaluate(ctx)
            if (result is Value.Bool && result.v) desired[rule.alias] = true
        }

        val previous = lastState
        val changed = previous.activeByAlias != desired
        lastState = GateState(activeByAlias = desired, weightByAlias = emptyMap(), regimeName = null, changed = changed)
    }

    /** Tick-fed indicators are not yet supported inside portfolio WHEN rules. */
    fun onTick(tick: Tick) {
        // Reserved for future VWAP-style indicators. The gate's hub is candle-driven, so tick
        // updates would need to update tick-fed bindings directly here.
    }

    private object EmptySource : com.qkt.marketdata.source.MarketSource {
        override val name: String = "PortfolioGate"
        override val capabilities: Set<com.qkt.marketdata.source.MarketSourceCapability> = emptySet()
        override fun supports(symbol: String): Boolean = false
    }

    private object EmptyPositions : com.qkt.positions.StrategyPositionView {
        override fun positionFor(symbol: String): com.qkt.positions.Position? = null
        override fun allPositions(): Map<String, com.qkt.positions.Position> = emptyMap()
        override fun maeFor(symbol: String): BigDecimal? = null
    }

    private object EmptyPnL : com.qkt.pnl.StrategyPnLView {
        override fun realized(): BigDecimal = BigDecimal.ZERO
        override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO
        override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO
        override fun total(): BigDecimal = BigDecimal.ZERO
        override fun equity(): BigDecimal = BigDecimal.ZERO
        override fun balance(): BigDecimal = BigDecimal.ZERO
    }

    /** A point-in-time snapshot of which children should be active. */
    data class GateState(
        /** Alias -> true if the child should be allowed to trade. */
        val activeByAlias: Map<String, Boolean>,
        /** Alias -> allocation weight when the portfolio uses continuous/probabilistic regimes. */
        val weightByAlias: Map<String, BigDecimal>,
        /** Human-readable name of the currently dominant regime, if any. */
        val regimeName: String?,
        /** True if [activeByAlias] or [weightByAlias] changed on this evaluation. */
        val changed: Boolean,
    ) {
        companion object {
            fun empty(): GateState = GateState(emptyMap(), emptyMap(), null, false)
        }
    }

    private companion object {
        const val GATE_OWNER = "portfolio-gate"
        const val DEFAULT_RETENTION = 2
    }
}
