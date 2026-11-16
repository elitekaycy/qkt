package com.qkt.verification

import com.qkt.common.Money
import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.dsl.stdlib.IndicatorRegistry
import com.qkt.indicators.BiIndicator
import com.qkt.indicators.Indicator
import com.qkt.indicators.IndicatorOutput
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/**
 * Drives a fixture's indicator through the same `IndicatorRegistry.create` path the DSL compiler uses (so
 * the wrappers, the Donchian one-bar lag and multi-line extraction are what is tested) and reads `value()`
 * after every bar, exactly as a rule would. The fixture's `input` says which side of each bar is fed:
 * `close`, `candle`, `tick` (one tick per bar at the close carrying the bar's volume), `pair:close,open`
 * (two-series indicators) or `bool:close>open` (boolean-series indicators).
 */
object OracleReplay {
    fun candles(series: OracleSeries): List<Candle> =
        series.bars.map { b ->
            val start = b[0].toLong()
            Candle(
                symbol = series.id,
                open = Money.of(b[1]),
                high = Money.of(b[2]),
                low = Money.of(b[3]),
                close = Money.of(b[4]),
                volume = Money.of(b[5]),
                startTime = start,
                endTime = start + series.timeframeMs,
            )
        }

    fun run(
        fixture: IndicatorFixture,
        series: OracleSeries,
    ): List<BigDecimal?> {
        val indicator = IndicatorRegistry.create(fixture.indicator, fixture.args.map { BigDecimal(it) })
        return candles(series).map { c ->
            feed(indicator, fixture.input, c)
            indicator.value()
        }
    }

    fun invoke(
        function: String,
        args: List<String>,
    ): BigDecimal? = FuncRegistry.invoke(function, args.map { BigDecimal(it) })

    @Suppress("UNCHECKED_CAST")
    private fun feed(
        indicator: IndicatorOutput,
        input: String,
        c: Candle,
    ) {
        when (input) {
            "close" -> (indicator as Indicator<BigDecimal>).update(c.close)
            "candle" -> (indicator as Indicator<Candle>).update(c)
            "tick" -> (indicator as Indicator<Tick>).update(Tick(c.symbol, c.close, c.startTime, c.volume))
            "pair:close,open" -> (indicator as BiIndicator).update(c.close, c.open)
            "bool:close>open" -> (indicator as Indicator<Boolean>).update(c.close > c.open)
            else -> error("unknown fixture input: $input")
        }
    }
}
