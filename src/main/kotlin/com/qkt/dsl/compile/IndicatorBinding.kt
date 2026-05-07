package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.stdlib.IndicatorInput
import com.qkt.dsl.stdlib.IndicatorRegistry
import com.qkt.indicators.Indicator
import com.qkt.indicators.IndicatorOutput
import com.qkt.marketdata.Candle
import java.math.BigDecimal

class IndicatorBinding(
    val call: IndicatorCall,
    val indicator: IndicatorOutput,
    val streamAlias: String,
    val field: String?,
    val inputKind: IndicatorInput,
) {
    @Suppress("UNCHECKED_CAST")
    fun update(ctx: EvalContext) {
        val symbol = ctx.streamSymbols[streamAlias] ?: error("Unknown stream alias: $streamAlias")
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
                        "Indicator ${call.name} non-series arg must be a numeric literal in 11b"
                    }
                    it.value
                }
            val streamAlias: String
            val field: String?
            when (spec.inputKind) {
                IndicatorInput.NUMERIC_SERIES -> {
                    require(seriesArg is StreamFieldRef) {
                        "Indicator ${call.name} series arg must be a stream field in 11b"
                    }
                    streamAlias = seriesArg.stream
                    field = seriesArg.field
                }
                IndicatorInput.CANDLE_SERIES -> {
                    require(seriesArg is StreamFieldRef && seriesArg.field == "candle") {
                        "Indicator ${call.name} series arg must be the whole stream in 11b"
                    }
                    streamAlias = seriesArg.stream
                    field = null
                }
            }
            val ind = IndicatorRegistry.create(call.name, constArgs)
            val binding = IndicatorBinding(call, ind, streamAlias, field, spec.inputKind)
            bindings.add(binding)
            return binding
        }

        fun updateAll(ctx: EvalContext) {
            for (b in bindings) b.update(ctx)
        }
    }
}
