package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.stdlib.IndicatorInput
import com.qkt.dsl.stdlib.IndicatorRegistry
import com.qkt.dsl.stdlib.IndicatorSpec
import com.qkt.indicators.IndicatorOutput

/**
 * Turns an indicator call into an [IndicatorBinding]: looks up the registry spec, checks the
 * call's arity, constant arguments and series argument against it, and builds the indicator.
 * [IndicatorBinding.Bag] owns the resulting bindings; this object holds no state.
 */
internal object IndicatorCallBinder {
    fun bind(
        call: IndicatorCall,
        volumeAliases: MutableSet<String>,
        bindInner: (IndicatorCall) -> IndicatorBinding,
    ): IndicatorBinding {
        val spec = IndicatorRegistry.spec(call.name) ?: error("Unknown indicator: ${call.name}")
        require(call.args.size == spec.arity) {
            "Indicator ${call.name} expects ${spec.arity} args, got ${call.args.size}"
        }
        val seriesArg = call.args.first()
        if (spec.requiresVolume && seriesArg is StreamFieldRef) volumeAliases.add(seriesArg.stream)
        val constArgs =
            call.args.drop(1).map {
                require(it is NumLit) {
                    "Indicator ${call.name} non-series arg must be a numeric literal"
                }
                it.value
            }
        val ind = IndicatorRegistry.create(call.name, constArgs)
        return when (seriesArg) {
            is StreamFieldRef -> bindStream(spec, call, seriesArg, ind)
            is IndicatorCall -> bindIndicator(spec, call, seriesArg, ind, bindInner)
            else ->
                error(
                    "Indicator ${call.name} series arg must be a stream field, another indicator call, " +
                        "or routed via Bag.bindExpression for arbitrary expressions",
                )
        }
    }

    /**
     * Bind a two-series indicator (#319, e.g. correlation/beta). Both series are pre-compiled
     * by [ExprCompiler] into [CompiledExpr]s and fed as an aligned pair each bar; [primaryAlias]
     * gates the update to the first series' stream. Only NUMERIC_SERIES specs are supported.
     */
    fun bindPair(
        call: IndicatorCall,
        seriesExprA: CompiledExpr,
        seriesExprB: CompiledExpr,
        primaryAlias: String,
    ): IndicatorBinding {
        val spec = IndicatorRegistry.spec(call.name) ?: error("Unknown indicator: ${call.name}")
        require(call.args.size == spec.arity) {
            "Indicator ${call.name} expects ${spec.arity} args, got ${call.args.size}"
        }
        require(spec.inputKind == IndicatorInput.NUMERIC_SERIES) {
            "Indicator ${call.name} two-series binding only supports NUMERIC_SERIES"
        }
        val constArgs =
            call.args.drop(spec.seriesCount).map {
                require(it is NumLit) { "Indicator ${call.name} non-series arg must be a numeric literal" }
                it.value
            }
        val ind = IndicatorRegistry.create(call.name, constArgs)
        return IndicatorBindingFactory.seriesFedPair(call, ind, primaryAlias, seriesExprA, seriesExprB)
    }

    /**
     * Bind an indicator whose series arg is an arbitrary numeric expression (#174).
     *
     * Used by [ExprCompiler] when the series arg is neither a [StreamFieldRef] nor
     * an [IndicatorCall] — e.g. \`stddev(gold.close - 75 * silver.close, 60)\`.
     *
     * [primaryAlias] gates updates: the binding fires once per bar when the closing
     * bar belongs to that stream. Cross-stream expressions evaluate against the
     * latest known candle for each referenced stream. [IndicatorInput.NUMERIC_SERIES] and
     * [IndicatorInput.BOOLEAN_SERIES] specs are supported; CANDLE_SERIES / TICK_SERIES
     * indicators (e.g. ATR, VWAP) still require their native stream-field path.
     */
    fun bindExpression(
        call: IndicatorCall,
        seriesExpr: CompiledExpr,
        primaryAlias: String,
    ): IndicatorBinding {
        val spec = IndicatorRegistry.spec(call.name) ?: error("Unknown indicator: ${call.name}")
        require(call.args.size == spec.arity) {
            "Indicator ${call.name} expects ${spec.arity} args, got ${call.args.size}"
        }
        require(
            spec.inputKind == IndicatorInput.NUMERIC_SERIES ||
                spec.inputKind == IndicatorInput.BOOLEAN_SERIES,
        ) {
            "Indicator ${call.name} requires ${spec.inputKind}; expression-fed binding only " +
                "supports NUMERIC_SERIES and BOOLEAN_SERIES indicators"
        }
        val constArgs =
            call.args.drop(1).map {
                require(it is NumLit) {
                    "Indicator ${call.name} non-series arg must be a numeric literal"
                }
                it.value
            }
        val ind = IndicatorRegistry.create(call.name, constArgs)
        return IndicatorBindingFactory.expressionFed(call, ind, primaryAlias, seriesExpr, spec.inputKind)
    }

    private fun bindStream(
        spec: IndicatorSpec,
        call: IndicatorCall,
        seriesArg: StreamFieldRef,
        ind: IndicatorOutput,
    ): IndicatorBinding =
        when (spec.inputKind) {
            IndicatorInput.NUMERIC_SERIES -> {
                // A bar time is not a price series: only a lookback (`btc.timestamp[3]`, which
                // lowers to lag) may read it; `ema(btc.timestamp, 9)` stays an error (#1130).
                val timestampLookback = seriesArg.field == "timestamp" && call.name.equals("LAG", ignoreCase = true)
                require(
                    timestampLookback ||
                        seriesArg.field in setOf("close", "value", "open", "high", "low", "volume", "price"),
                ) {
                    "Indicator ${call.name} series field must be numeric: got ${seriesArg.field}"
                }
                IndicatorBindingFactory.streamFed(call, ind, seriesArg.stream, seriesArg.field, spec.inputKind)
            }
            IndicatorInput.CANDLE_SERIES -> {
                require(seriesArg.field == "candle") {
                    "Indicator ${call.name} series arg must be the whole stream (use stream.candle or atr(stream))"
                }
                IndicatorBindingFactory.streamFed(call, ind, seriesArg.stream, null, spec.inputKind)
            }
            IndicatorInput.BOOLEAN_SERIES -> {
                error("Indicator ${call.name} requires a condition expression")
            }
            IndicatorInput.TICK_SERIES -> {
                require(seriesArg.field == "tick") {
                    "Indicator ${call.name} requires a tick series; use ${call.name.lowercase()}(${seriesArg.stream}.tick, …)"
                }
                IndicatorBindingFactory.streamFed(call, ind, seriesArg.stream, null, spec.inputKind)
            }
        }

    private fun bindIndicator(
        spec: IndicatorSpec,
        call: IndicatorCall,
        inner: IndicatorCall,
        ind: IndicatorOutput,
        bindInner: (IndicatorCall) -> IndicatorBinding,
    ): IndicatorBinding {
        require(spec.inputKind == IndicatorInput.NUMERIC_SERIES) {
            "Indicator ${call.name} requires a candle series; cannot accept another indicator's output"
        }
        val innerBinding = bindInner(inner)
        return IndicatorBindingFactory.indicatorFed(call, ind, innerBinding)
    }
}
