package com.qkt.parity

import com.qkt.marketdata.Candle
import java.math.BigDecimal

/** Symbols, flat candle tapes and expected-leg builders shared by the TIMES entry parity tests. */
internal object TimesEntryFixtures {
    fun leg(
        side: String,
        symbol: String,
        n: Int,
    ) = List(n) { side to symbol }

    fun x(vararg closes: String) = mapOf(X to candles(X, closes.toList()))

    val bracket = "BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }"

    const val X = "BACKTEST:X"
    const val Y = "BACKTEST:Y"

    fun candles(
        symbol: String,
        closes: List<String>,
    ): List<Candle> =
        closes.mapIndexed { index, close ->
            val price = BigDecimal(close)
            Candle(
                symbol = symbol,
                open = price,
                high = price,
                low = price,
                close = price,
                volume = BigDecimal.ONE,
                startTime = index * 60_000L,
                endTime = (index + 1) * 60_000L,
            )
        }
}
