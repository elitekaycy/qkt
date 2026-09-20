package com.qkt.dsl.stdlib

import com.qkt.indicators.Indicator
import com.qkt.indicators.IndicatorOutput
import java.math.BigDecimal

/**
 * Maps DSL indicator names to factory functions that build runtime instances.
 *
 * Each [IndicatorSpec] carries the indicator's DSL name (uppercase), the input
 * kind it consumes (numeric series or candle series), the call arity in the
 * DSL (including the leading value/stream argument), and a factory that takes
 * the remaining constant arguments and constructs the indicator.
 *
 * MACD and Bollinger Bands are multi-output indicators. Each output gets its
 * own DSL name (`MACD`, `MACD_SIGNAL`, `MACD_HIST` / `BOLLINGER_UPPER`,
 * `BOLLINGER_MIDDLE`, `BOLLINGER_LOWER`) and constructs a thin
 * [Indicator]<[BigDecimal]> wrapper that delegates to one underlying instance
 * and exposes the desired output.
 */
object IndicatorRegistry {
    private val table: Map<String, IndicatorSpec> =
        (
            seriesIndicatorSpecs +
                candleOscillatorSpecs +
                channelIndicatorSpecs +
                sessionIndicatorSpecs +
                donchianIndicatorSpecs
        ).toMap()

    /** Every registered indicator name (uppercase), for editor tooling (completion, hover). */
    fun names(): Set<String> = table.keys

    fun has(name: String): Boolean = table.containsKey(name.uppercase())

    fun spec(name: String): IndicatorSpec? = table[name.uppercase()]

    fun create(
        name: String,
        constArgs: List<BigDecimal>,
    ): IndicatorOutput {
        val s = spec(name) ?: error("Unknown indicator: $name")
        val expectedConst = s.arity - s.seriesCount
        require(constArgs.size == expectedConst) {
            "Indicator $name expects $expectedConst constant args, got ${constArgs.size}"
        }
        return s.factory(constArgs)
    }
}
