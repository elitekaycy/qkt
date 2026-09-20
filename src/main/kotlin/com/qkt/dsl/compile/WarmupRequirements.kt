package com.qkt.dsl.compile

import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.ast.WhenThen

/**
 * Derives how many closed bars per stream alias a strategy needs to be warm.
 *
 * Combines three sources, taking the max per alias:
 * - Explicit `WARMUP N BARS` on the `StreamDecl`.
 * - Indicator warmup, read from the indicator's own [com.qkt.indicators.IndicatorOutput.warmupBars]
 *   via the registry (exact for multi-window indicators — MACD(12,26,9) needs 34
 *   bars, not 26). Walks every rule CONDITION, every rule ACTION (sizing, order
 *   prices, bracket/OCO child prices, stack specs — `BRACKET ... BY atr(...)` counts),
 *   and every `LET` expression, with strategy DEFAULTS merged in first.
 *
 * Chained and expression-fed indicators compose: `EMA(EMA(close, 9), 21)` needs 30
 * bars because the outer window only starts filling once the inner one is defined.
 * Across streams the outer span is converted into each inner stream's bars, so
 * `percentile_rank(s.close / lag(o.close, 2), 5)` on a 1m `s` and 5m `o` needs 5
 * bars of `s` and 4 of `o`.
 *
 * Lookback indices (`stream.close[N]`) are not yet derived — set explicit
 * `WARMUP N BARS` to cover them.
 */
object WarmupRequirements {
    fun compute(ast: StrategyAst): Map<String, Int> {
        timeframeMinutes.set(
            (
                ast.streams.map { stream -> stream.alias to stream.timeframe } +
                    ast.series.map { series -> series.alias to series.timeframe }
            ).associate { (alias, timeframe) ->
                alias to
                    com.qkt.candles.TimeWindow
                        .parse(timeframe)
                        .durationMs
                        .div(60_000L)
                        .coerceAtLeast(1L)
            },
        )
        lets.set(ast.lets.associate { it.name to it.expr })
        streamAliases.set(
            (ast.streams.map { it.alias } + ast.baskets.map { it.alias } + ast.series.map { it.alias }).toSet(),
        )
        val out = mutableMapOf<String, Int>()
        try {
            for (s in ast.streams) {
                val w = s.warmupBars ?: 0
                if (w > 0) merge(out, s.alias, w)
            }
            for (rule in ast.rules) walkRule(rule, ast, out)
            for (sequence in ast.sequences) {
                for (stage in sequence.stages) walkExpr(stage.condition, out)
            }
            for (let in ast.lets) walkExpr(let.expr, out)
            return out.toMap()
        } finally {
            timeframeMinutes.remove()
            lets.remove()
            streamAliases.remove()
        }
    }

    private fun walkRule(
        rule: RuleAst,
        ast: StrategyAst,
        out: MutableMap<String, Int>,
    ) {
        if (rule !is WhenThen) return
        walkExpr(rule.cond, out)
        // Action-side indicators warm the gate too: an ATR inside a bracket child
        // price computes garbage on a half-warm window exactly like one in the
        // condition. DEFAULTS merge first so `DEFAULTS { STOP_LOSS = BY ATR(...) }`
        // counts for every action it applies to.
        visitActionExprs(mergeDefaults(rule.action, ast.defaults)) { walkExpr(it, out) }
    }

    private fun walkExpr(
        expr: ExprAst,
        out: MutableMap<String, Int>,
    ) {
        when (expr) {
            is IndicatorCall -> {
                val alias = aliasFor(expr)
                val period = registryWarmupBars(expr, alias?.let { timeframeMinutes.get()[it] }) ?: numLitMax(expr)
                // An expression-fed window only starts filling once every indicator inside
                // its series expression is defined: `percentile_rank(lag(o, 160) …, 160)`
                // needs 161 + 160 closes on `o`, not the max of the two. The window advances
                // on the primary alias's closes, so each inner stream must stay defined for
                // the last `period` primary bars: its own depth plus that span in its bars.
                val nested = mutableMapOf<String, Int>()
                expr.args.forEach { walkExpr(it, nested) }
                if (period == null) {
                    nested.forEach { (innerAlias, innerBars) -> merge(out, innerAlias, innerBars) }
                } else {
                    val tf = timeframeMinutes.get()
                    val primaryMinutes = alias?.let { tf[it] } ?: 1L
                    for (innerAlias in nested.keys + listOfNotNull(alias)) {
                        val innerMinutes = tf[innerAlias] ?: primaryMinutes
                        val span = ((period * primaryMinutes + innerMinutes - 1) / innerMinutes).toInt()
                        merge(out, innerAlias, (nested[innerAlias] ?: 0) + span)
                    }
                }
            }
            is BinaryOp -> {
                walkExpr(expr.lhs, out)
                walkExpr(expr.rhs, out)
            }
            is UnaryOp -> walkExpr(expr.arg, out)
            is CmpOp -> {
                walkExpr(expr.lhs, out)
                walkExpr(expr.rhs, out)
            }
            is Between -> {
                walkExpr(expr.v, out)
                walkExpr(expr.lo, out)
                walkExpr(expr.hi, out)
            }
            is InList -> {
                walkExpr(expr.v, out)
                expr.members.forEach { walkExpr(it, out) }
            }
            is Crosses -> {
                walkExpr(expr.lhs, out)
                walkExpr(expr.rhs, out)
            }
            is CaseWhen -> {
                expr.branches.forEach { (c, v) ->
                    walkExpr(c, out)
                    walkExpr(v, out)
                }
                walkExpr(expr.elseExpr, out)
            }
            is Aggregate -> {
                walkExpr(expr.series, out)
                // A `SINCE T-N` window is undefined until it holds N closed bars, so the
                // stream it runs on needs N bars of warmup like an N-period indicator. The
                // walker used to skip the window, and a strategy on `mean(x) SINCE T-200`
                // started live without the history its first decision reads.
                val window = expr.window
                if (window is SinceTPast) aliasFor(expr.series)?.let { merge(out, it, window.n) }
            }
            is FuncCall -> expr.args.forEach { walkExpr(it, out) }
            is IsNull -> walkExpr(expr.expr, out)
            else -> Unit
        }
    }

    private fun aliasFor(expr: ExprAst): String? =
        when (expr) {
            is StreamFieldRef -> expr.stream
            is IndicatorCall -> expr.args.firstOrNull()?.let(::aliasFor)
            is BinaryOp -> aliasFor(expr.lhs) ?: aliasFor(expr.rhs)
            is UnaryOp -> aliasFor(expr.arg)
            is CmpOp -> aliasFor(expr.lhs) ?: aliasFor(expr.rhs)
            is Between -> aliasFor(expr.v) ?: aliasFor(expr.lo) ?: aliasFor(expr.hi)
            is InList -> aliasFor(expr.v) ?: expr.members.firstNotNullOfOrNull(::aliasFor)
            is Crosses -> aliasFor(expr.lhs) ?: aliasFor(expr.rhs)
            is CaseWhen ->
                expr.branches.firstNotNullOfOrNull { (condition, value) ->
                    aliasFor(condition) ?: aliasFor(value)
                } ?: aliasFor(expr.elseExpr)
            is Aggregate -> aliasFor(expr.series)
            is FuncCall -> expr.args.firstNotNullOfOrNull(::aliasFor)
            is IsNull -> aliasFor(expr.expr)
            is Ref ->
                lets.get()[expr.name]?.let(::aliasFor)
                    ?: expr.name.takeIf { it in streamAliases.get() }
            else -> null
        }

    private fun merge(
        out: MutableMap<String, Int>,
        alias: String,
        bars: Int,
    ) {
        out[alias] = maxOf(out[alias] ?: 0, bars)
    }

    private val timeframeMinutes = ThreadLocal.withInitial<Map<String, Long>> { emptyMap() }
    private val lets = ThreadLocal.withInitial<Map<String, ExprAst>> { emptyMap() }
    private val streamAliases = ThreadLocal.withInitial<Set<String>> { emptySet() }
}
