package com.qkt.dsl.stdlib

import com.qkt.indicators.IndicatorOutput
import com.qkt.indicators.catalog.ATR
import com.qkt.indicators.catalog.EMA
import com.qkt.indicators.catalog.RSI
import java.math.BigDecimal

enum class IndicatorInput { NUMERIC_SERIES, CANDLE_SERIES }

data class IndicatorSpec(
    val name: String,
    val inputKind: IndicatorInput,
    val arity: Int,
    val factory: (List<BigDecimal>) -> IndicatorOutput,
)

object IndicatorRegistry {
    private val table: Map<String, IndicatorSpec> =
        mapOf(
            "EMA" to
                IndicatorSpec("EMA", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    EMA(period = args[0].toInt())
                },
            "RSI" to
                IndicatorSpec("RSI", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                    RSI(period = args[0].toInt())
                },
            "ATR" to
                IndicatorSpec("ATR", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                    ATR(period = args[0].toInt())
                },
        )

    fun has(name: String): Boolean = table.containsKey(name)

    fun spec(name: String): IndicatorSpec? = table[name]

    fun create(
        name: String,
        constArgs: List<BigDecimal>,
    ): IndicatorOutput {
        val s = spec(name) ?: error("Unknown indicator: $name")
        require(constArgs.size == s.arity - 1) {
            "Indicator $name expects ${s.arity - 1} constant args, got ${constArgs.size}"
        }
        return s.factory(constArgs)
    }
}
