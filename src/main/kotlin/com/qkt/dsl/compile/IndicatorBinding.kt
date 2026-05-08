package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.stdlib.IndicatorInput
import com.qkt.dsl.stdlib.IndicatorRegistry
import com.qkt.dsl.stdlib.IndicatorSpec
import com.qkt.indicators.Indicator
import com.qkt.indicators.IndicatorOutput
import com.qkt.marketdata.Candle
import java.math.BigDecimal

class IndicatorBinding private constructor(
    val call: IndicatorCall,
    val indicator: IndicatorOutput,
    private val streamAlias: String?,
    private val field: String?,
    private val inputKind: IndicatorInput,
    private val source: IndicatorBinding?,
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
        val symbol = ctx.streams[streamAlias!!]?.symbol ?: error("Unknown stream alias: $streamAlias")
        if (ctx.candle.symbol != symbol) return
        when (inputKind) {
            IndicatorInput.NUMERIC_SERIES -> {
                val v: BigDecimal =
                    when (field) {
                        "close", "price" -> ctx.candle.close
                        "open" -> ctx.candle.open
                        "high" -> ctx.candle.high
                        "low" -> ctx.candle.low
                        "volume" -> ctx.candle.volume
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
        }
    }

    companion object {
        internal fun streamFed(
            call: IndicatorCall,
            indicator: IndicatorOutput,
            streamAlias: String,
            field: String?,
            inputKind: IndicatorInput,
        ): IndicatorBinding = IndicatorBinding(call, indicator, streamAlias, field, inputKind, source = null)

        internal fun indicatorFed(
            call: IndicatorCall,
            indicator: IndicatorOutput,
            source: IndicatorBinding,
        ): IndicatorBinding =
            IndicatorBinding(
                call,
                indicator,
                streamAlias = null,
                field = null,
                inputKind = IndicatorInput.NUMERIC_SERIES,
                source = source,
            )
    }

    class Bag {
        private val bindings: MutableList<IndicatorBinding> = mutableListOf()

        fun bind(call: IndicatorCall): IndicatorBinding {
            val spec = IndicatorRegistry.spec(call.name) ?: error("Unknown indicator: ${call.name}")
            require(call.args.size == spec.arity) {
                "Indicator ${call.name} expects ${spec.arity} args, got ${call.args.size}"
            }
            val seriesArg = call.args.first()
            val constArgs =
                call.args.drop(1).map {
                    require(it is NumLit) {
                        "Indicator ${call.name} non-series arg must be a numeric literal"
                    }
                    it.value
                }
            val ind = IndicatorRegistry.create(call.name, constArgs)
            val binding =
                when (seriesArg) {
                    is StreamFieldRef -> bindStream(spec, call, seriesArg, ind)
                    is IndicatorCall -> bindIndicator(spec, call, seriesArg, ind)
                    else ->
                        error(
                            "Indicator ${call.name} series arg must be a stream field or another indicator call",
                        )
                }
            bindings.add(binding)
            return binding
        }

        private fun bindStream(
            spec: IndicatorSpec,
            call: IndicatorCall,
            seriesArg: StreamFieldRef,
            ind: IndicatorOutput,
        ): IndicatorBinding =
            when (spec.inputKind) {
                IndicatorInput.NUMERIC_SERIES -> {
                    require(seriesArg.field in setOf("close", "open", "high", "low", "volume", "price")) {
                        "Indicator ${call.name} series field must be numeric: got ${seriesArg.field}"
                    }
                    streamFed(call, ind, seriesArg.stream, seriesArg.field, spec.inputKind)
                }
                IndicatorInput.CANDLE_SERIES -> {
                    require(seriesArg.field == "candle") {
                        "Indicator ${call.name} series arg must be the whole stream (use stream.candle or atr(stream))"
                    }
                    streamFed(call, ind, seriesArg.stream, null, spec.inputKind)
                }
            }

        private fun bindIndicator(
            spec: IndicatorSpec,
            call: IndicatorCall,
            inner: IndicatorCall,
            ind: IndicatorOutput,
        ): IndicatorBinding {
            require(spec.inputKind == IndicatorInput.NUMERIC_SERIES) {
                "Indicator ${call.name} requires a candle series; cannot accept another indicator's output"
            }
            val innerBinding = bind(inner)
            return indicatorFed(call, ind, innerBinding)
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
    }
}
