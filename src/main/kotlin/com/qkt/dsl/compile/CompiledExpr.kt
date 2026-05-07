package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import java.math.BigDecimal

sealed interface Value {
    data class Num(val v: BigDecimal) : Value

    data class Bool(val v: Boolean) : Value

    data object Undefined : Value
}

class EvalContext(
    val candle: Candle,
    val streamSymbols: Map<String, String>,
    val lets: Map<String, BigDecimal>,
)

fun interface CompiledExpr {
    fun evaluate(ctx: EvalContext): Value
}
