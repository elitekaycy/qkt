package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.indicators.catalog.ConfirmRatio
import com.qkt.indicators.catalog.OlsResidual

/**
 * Compiles indicator calls (`ema(gold.close, 20)`, `zscore(resid(...), 96)`, ...) by binding
 * each call into the shared [IndicatorBinding.Bag] and returning a closure that reads the bound
 * indicator's cached value. Series arguments that are expressions compile through [exprs].
 */
internal class IndicatorCallCompiler(
    private val bindings: IndicatorBinding.Bag,
    private val exprs: ExprCompiler,
) {
    fun compile(call: IndicatorCall): CompiledExpr {
        if (call.name.equals("RESID", ignoreCase = true)) return compileResidual(call)
        if (call.name.equals("CONFIRM_RATIO", ignoreCase = true)) return compileConfirmRatio(call)
        val spec =
            com.qkt.dsl.stdlib.IndicatorRegistry
                .spec(call.name)
        if (spec != null) validateIntegerIndicatorArgs(call, spec.seriesCount)
        val binding =
            if (spec != null && spec.seriesCount >= 2) {
                // #319 two-series (e.g. correlation): compile both series args and bind as a pair,
                // gated on the first series' stream. Each series may itself be a cross-stream expr.
                val primaryAlias =
                    streamAliasesIn(call.args[0]).firstOrNull()
                        ?: error("Indicator ${call.name} first series must reference a stream")
                bindings.bindPair(
                    call,
                    exprs.compile(call.args[0], null),
                    exprs.compile(call.args[1], null),
                    primaryAlias,
                )
            } else {
                when (val seriesArg = call.args.firstOrNull()) {
                    is StreamFieldRef, null -> bindings.bind(call)
                    // A registry indicator nested inside another (e.g. zscore(ema(...), N)) feeds
                    // the outer indicator from the compiled inner expression. This also covers
                    // expression-fed inner indicators such as runlength_where(close < sma(...)).
                    is IndicatorCall ->
                        bindings.bindExpression(
                            call,
                            exprs.compile(seriesArg, null),
                            streamAliasesIn(seriesArg).firstOrNull()
                                ?: error("Indicator ${call.name} series must reference a stream"),
                        )
                    else -> {
                        // #174 expression-fed: compile the series expression and bind via
                        // primary alias. Gate on the first StreamFieldRef the expression
                        // references; reject if it references no stream (caller mistake).
                        val primaryAlias =
                            streamAliasesIn(seriesArg).firstOrNull()
                                ?: error(
                                    "Indicator ${call.name} expression-fed series must reference at least one " +
                                        "stream (e.g. stddev(gold.close - silver.close, 60))",
                                )
                        bindings.bindExpression(call, exprs.compile(seriesArg, null), primaryAlias)
                    }
                }
            }
        return CompiledExpr {
            val v = binding.indicator.value()
            if (v == null || !binding.indicator.isReady) Value.Undefined else Value.Num(v)
        }
    }

    private fun validateIntegerIndicatorArgs(
        call: IndicatorCall,
        seriesCount: Int,
    ) {
        val fractionalIndexes =
            when (call.name.uppercase()) {
                "KELTNER_UPPER", "KELTNER_MIDDLE", "KELTNER_LOWER",
                "BOLLINGER_UPPER", "BOLLINGER_MIDDLE", "BOLLINGER_LOWER",
                -> setOf(1)
                else -> emptySet()
            }
        call.args.drop(seriesCount).forEachIndexed { index, arg ->
            if (index in fractionalIndexes) return@forEachIndexed
            if (arg is NumLit) {
                require(arg.value.stripTrailingZeros().scale() <= 0) {
                    "Indicator ${call.name} argument ${index + 1} must be an integer literal; got ${arg.value}"
                }
            }
        }
    }

    /**
     * Compile a multi-regressor OLS residual: `resid(dependent, regressor1, …, period)` (#474).
     *
     * The trailing argument is an integer lookback; the first series is the dependent and the
     * rest are regressors (at least one). Each series may be any cross-stream expression, exactly
     * like a `zscore` series, so `resid` composes with `zscore` to z-score the residual. Gated on
     * the dependent series' primary stream, like other expression-fed indicators.
     *
     * e.g. `zscore(resid(gbp.close, eur.close, aud.close, 96), 96)` — z-score of the part of GBP
     * the EUR/AUD factors do not explain.
     */
    private fun compileResidual(call: IndicatorCall): CompiledExpr {
        require(call.args.size >= 3) {
            "resid needs a dependent series, at least one regressor, and a period: resid(dep, reg, N)"
        }
        val periodArg = call.args.last()
        require(periodArg is NumLit && periodArg.value.stripTrailingZeros().scale() <= 0) {
            "resid period (last argument) must be an integer literal: resid(dep, reg, N)"
        }
        val period = periodArg.value.toInt()
        val seriesArgs = call.args.dropLast(1)
        val regressorCount = seriesArgs.size - 1
        require(period > regressorCount + 1) {
            "resid period ($period) must exceed the number of regressors plus one ($regressorCount + 1)"
        }
        val primaryAlias =
            streamAliasesIn(seriesArgs.first()).firstOrNull()
                ?: error("resid dependent series must reference a stream (e.g. resid(gbp.close, eur.close, 96))")
        val indicator = OlsResidual(period = period, regressorCount = regressorCount)
        val seriesExprs = seriesArgs.map { exprs.compile(it, null) }
        val binding = bindings.bindMulti(call, indicator, seriesExprs, primaryAlias)
        return CompiledExpr {
            val v = binding.indicator.value()
            if (v == null || !binding.indicator.isReady) Value.Undefined else Value.Num(v)
        }
    }

    /**
     * Compile a cross-symbol confirmation ratio: `confirm_ratio(signal, peer1, …, N)` (#479).
     *
     * The trailing argument is an integer lookback; the first series is the signal and the rest
     * are peers (at least one). Each series may be any cross-stream expression — fold polarity for
     * inverse pairs into the peer expression, e.g. `confirm_ratio(eur.close, gbp.close, -chf.close, 4)`.
     * Gated on the signal series' primary stream, like other expression-fed indicators.
     */
    private fun compileConfirmRatio(call: IndicatorCall): CompiledExpr {
        require(call.args.size >= 3) {
            "confirm_ratio needs a signal series, at least one peer, and a lookback: confirm_ratio(signal, peer, N)"
        }
        val periodArg = call.args.last()
        require(periodArg is NumLit && periodArg.value.stripTrailingZeros().scale() <= 0) {
            "confirm_ratio lookback (last argument) must be an integer literal: confirm_ratio(signal, peer, N)"
        }
        val period = periodArg.value.toInt()
        val seriesArgs = call.args.dropLast(1)
        val peerCount = seriesArgs.size - 1
        val primaryAlias =
            streamAliasesIn(seriesArgs.first()).firstOrNull()
                ?: error(
                    "confirm_ratio signal series must reference a stream (e.g. confirm_ratio(eur.close, gbp.close, 4))",
                )
        val indicator = ConfirmRatio(period = period, peerCount = peerCount)
        val seriesExprs = seriesArgs.map { exprs.compile(it, null) }
        val binding = bindings.bindMulti(call, indicator, seriesExprs, primaryAlias)
        return CompiledExpr {
            val v = binding.indicator.value()
            if (v == null || !binding.indicator.isReady) Value.Undefined else Value.Num(v)
        }
    }
}
