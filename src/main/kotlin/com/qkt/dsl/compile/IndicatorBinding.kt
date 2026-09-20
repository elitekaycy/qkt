package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.stdlib.IndicatorInput
import com.qkt.indicators.BiIndicator
import com.qkt.indicators.Indicator
import com.qkt.indicators.IndicatorOutput
import com.qkt.indicators.MultiIndicator
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/**
 * One DSL indicator call bound to its runtime indicator and to the feed that updates it each
 * bar (or each tick, for tick-fed indicators). [Bag] collects every binding a strategy compiles.
 */
class IndicatorBinding internal constructor(
    val call: IndicatorCall,
    val indicator: IndicatorOutput,
    private val streamAlias: String?,
    private val field: String?,
    private val inputKind: IndicatorInput,
    private val source: IndicatorBinding?,
    private val seriesExpr: CompiledExpr?,
    private val seriesExprB: CompiledExpr? = null,
    private val seriesExprsMulti: List<CompiledExpr>? = null,
) {
    val rootAlias: String? get() = streamAlias ?: source?.rootAlias

    @Suppress("UNCHECKED_CAST")
    fun update(ctx: EvalContext) {
        if (source != null) {
            if (!source.indicator.isReady) return
            val v = source.indicator.value() ?: return
            (indicator as Indicator<BigDecimal>).update(v)
            return
        }
        val symbol = ctx.streams[streamAlias!!]?.qktSymbol ?: error("Unknown stream alias: $streamAlias")
        if (ctx.candle.symbol != symbol) return
        // Two-series bindings (#319): feed the aligned (a, b) pair. Both compiled series evaluate
        // in the current context, so the second stream reads its latest closed candle via the hub.
        // Gated on the primary (first) stream's bar close, like the expression-fed path.
        if (seriesExprB != null) {
            val a = (seriesExpr!!.evaluate(ctx) as? Value.Num)?.v ?: return
            val b = (seriesExprB.evaluate(ctx) as? Value.Num)?.v ?: return
            (indicator as BiIndicator).update(a, b)
            return
        }
        // k-series bindings (#474): feed the aligned tuple of all series values (dependent first,
        // then regressors). If any series is undefined this bar, skip the update — the indicator
        // only advances on complete tuples, like the two-series path.
        if (seriesExprsMulti != null) {
            val values = ArrayList<BigDecimal>(seriesExprsMulti.size)
            for (e in seriesExprsMulti) {
                val v = (e.evaluate(ctx) as? Value.Num)?.v ?: return
                values.add(v)
            }
            (indicator as MultiIndicator).update(values)
            return
        }
        // Expression-fed bindings (#174): evaluate the compiled series expression in
        // the current context and feed the numeric result to the indicator. Updates
        // are gated to the *primary alias* (the first stream the expression references)
        // — for cross-stream expressions at the same timeframe, this yields one update
        // per bar with the latest known value from each stream.
        if (seriesExpr != null) {
            val v = seriesExpr.evaluate(ctx)
            when (inputKind) {
                IndicatorInput.NUMERIC_SERIES -> {
                    if (v is Value.Num) (indicator as Indicator<BigDecimal>).update(v.v)
                }
                IndicatorInput.BOOLEAN_SERIES -> {
                    if (v is Value.Bool) (indicator as Indicator<Boolean>).update(v.v)
                }
                IndicatorInput.CANDLE_SERIES, IndicatorInput.TICK_SERIES -> Unit
            }
            return
        }
        when (inputKind) {
            IndicatorInput.NUMERIC_SERIES -> {
                val v: BigDecimal =
                    when (field) {
                        "close", "price", "value" -> ctx.candle.close
                        "open" -> ctx.candle.open
                        "high" -> ctx.candle.high
                        "low" -> ctx.candle.low
                        "volume" -> ctx.candle.volume
                        "timestamp" -> BigDecimal.valueOf(ctx.candle.startTime)
                        else ->
                            error(
                                "Numeric indicator on stream '$streamAlias' requires a numeric field; got '$field'",
                            )
                    }
                (indicator as Indicator<BigDecimal>).update(v)
            }
            IndicatorInput.CANDLE_SERIES -> {
                (indicator as Indicator<Candle>).update(ctx.candle)
            }
            IndicatorInput.BOOLEAN_SERIES -> {
                error("Boolean indicator ${call.name} requires a condition expression")
            }
            IndicatorInput.TICK_SERIES -> {
                // Tick-fed indicators update on each raw tick via [updateFromTick],
                // not on candle close. Ignore the candle-driven update path.
            }
        }
    }

    /**
     * Update path for [IndicatorInput.TICK_SERIES] indicators. Called from
     * `CompiledStrategy.onTick` for every tick whose symbol matches this binding's
     * stream. No-op for non-tick-fed bindings.
     */
    @Suppress("UNCHECKED_CAST")
    fun updateFromTick(tick: Tick) {
        if (inputKind != IndicatorInput.TICK_SERIES) return
        if (source != null) return
        (indicator as Indicator<Tick>).update(tick)
    }

    /** True iff this binding consumes raw ticks (queried by CompiledStrategy.onTick). */
    internal fun isTickFed(): Boolean = inputKind == IndicatorInput.TICK_SERIES && source == null

    /**
     * Every indicator binding one strategy compiles, plus the stream aliases whose feed must carry
     * volume. Updates bindings on bar close by alias and hands tick-fed ones to the tick path.
     */
    class Bag {
        private val bindings: MutableList<IndicatorBinding> = mutableListOf()
        private val volumeAliases: MutableSet<String> = mutableSetOf()

        /** Stream aliases that a volume-weighted indicator (VWAP/OBV) binds to — the feed must supply volume. */
        val volumeRequiringAliases: Set<String> get() = volumeAliases

        /** Largest [IndicatorOutput.warmupBars] across all bound indicators; 0 if none. */
        val maxWarmupBars: Int
            get() = bindings.maxOfOrNull { it.indicator.warmupBars } ?: 0

        /** Binds [call] (and any indicator call nested as its series arg); see [IndicatorCallBinder.bind]. */
        fun bind(call: IndicatorCall): IndicatorBinding = keep(IndicatorCallBinder.bind(call, volumeAliases, ::bind))

        /** Binds a two-series indicator (#319) fed an aligned pair; see [IndicatorCallBinder.bindPair]. */
        fun bindPair(
            call: IndicatorCall,
            seriesExprA: CompiledExpr,
            seriesExprB: CompiledExpr,
            primaryAlias: String,
        ): IndicatorBinding = keep(IndicatorCallBinder.bindPair(call, seriesExprA, seriesExprB, primaryAlias))

        /**
         * Bind a k-series indicator (e.g. a multi-regressor OLS residual) whose series are all
         * arbitrary numeric expressions. The [indicator] is built by the caller — these indicators
         * have a variable series count and live outside the indicator registry — and each bar is fed
         * the aligned tuple of evaluated series values, gated on [primaryAlias]'s bar close.
         */
        fun bindMulti(
            call: IndicatorCall,
            indicator: IndicatorOutput,
            seriesExprs: List<CompiledExpr>,
            primaryAlias: String,
        ): IndicatorBinding = keep(IndicatorBindingFactory.seriesFedMulti(call, indicator, primaryAlias, seriesExprs))

        /** Binds an indicator fed an arbitrary series expression (#174); see [IndicatorCallBinder.bindExpression]. */
        fun bindExpression(
            call: IndicatorCall,
            seriesExpr: CompiledExpr,
            primaryAlias: String,
        ): IndicatorBinding = keep(IndicatorCallBinder.bindExpression(call, seriesExpr, primaryAlias))

        private fun keep(binding: IndicatorBinding): IndicatorBinding {
            bindings.add(binding)
            return binding
        }

        fun updateAll(ctx: EvalContext) {
            for (b in bindings) b.update(ctx)
        }

        fun updateForAlias(
            alias: String,
            ctx: EvalContext,
        ) {
            for (b in bindings) {
                if (b.rootAlias == alias) b.update(ctx)
            }
        }

        /**
         * Tick-fed bindings grouped by root alias. Bindings are added only at compile time (via
         * [bind]) and read only at runtime (per tick), so this is built once on first access instead
         * of re-filtering the full binding list on every tick.
         */
        private val tickFedByAlias: Map<String, List<IndicatorBinding>> by lazy {
            bindings.filter { it.isTickFed() }.groupBy { it.rootAlias ?: "" }
        }

        /** Bindings that consume raw ticks. Used by [CompiledStrategy.onTick] dispatch. */
        fun tickFedForAlias(alias: String): List<IndicatorBinding> = tickFedByAlias[alias] ?: emptyList()
    }
}
