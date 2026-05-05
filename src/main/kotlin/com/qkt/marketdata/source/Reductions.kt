package com.qkt.marketdata.source

import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

fun Sequence<Tick>.maxPrice(): BigDecimal? = maxOfOrNull { it.price }

fun Sequence<Tick>.minPrice(): BigDecimal? = minOfOrNull { it.price }

fun Sequence<Tick>.firstPrice(): BigDecimal? = firstOrNull()?.price

fun Sequence<Tick>.lastPrice(): BigDecimal? = lastOrNull()?.price

fun Sequence<Candle>.highestHigh(): BigDecimal? = maxOfOrNull { it.high }

fun Sequence<Candle>.lowestLow(): BigDecimal? = minOfOrNull { it.low }

fun Sequence<Candle>.firstOpen(): BigDecimal? = firstOrNull()?.open

fun Sequence<Candle>.lastClose(): BigDecimal? = lastOrNull()?.close

fun <I : Indicator<BigDecimal>> Sequence<BigDecimal>.runThrough(indicator: I): I {
    forEach { indicator.update(it) }
    return indicator
}
