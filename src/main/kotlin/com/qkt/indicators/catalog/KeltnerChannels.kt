package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/** The three Keltner Channel lines: an EMA midline and ATR-width bands around it. */
data class KeltnerBands(
    val upper: BigDecimal,
    val middle: BigDecimal,
    val lower: BigDecimal,
)

/**
 * Keltner Channels — an [EMA] midline of the close with bands set [atrMult] × [ATR] above and
 * below it. Like Bollinger Bands but volatility-scaled by ATR (true range) instead of standard
 * deviation, so the bands widen with trading range rather than dispersion of closes.
 *
 * middle = EMA(close, period); upper = middle + atrMult·ATR; lower = middle − atrMult·ATR.
 * Common use: trend/breakout (close outside a band) or mean-reversion (fade touches of a band).
 * On flat candles ATR is 0 and all three lines collapse to the EMA. Returns null until warmed up.
 */
class KeltnerChannels(
    private val period: Int,
    private val atrMult: BigDecimal,
) : Indicator<Candle> {
    init {
        require(period > 0) { "KeltnerChannels.period must be > 0: $period" }
    }

    private val ema = EMA(period)
    private val atr = ATR(period)

    override val warmupBars: Int = period + 1

    override val isReady: Boolean
        get() = ema.isReady && atr.isReady

    override fun update(input: Candle) {
        ema.update(input.close)
        atr.update(input)
    }

    /** The three bands, or null until both the EMA and ATR are computable. */
    fun bands(): KeltnerBands? {
        val middle = ema.value() ?: return null
        val range = atr.value() ?: return null
        val offset = atrMult.multiply(range, Money.CONTEXT)
        return KeltnerBands(
            upper = middle.add(offset, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING),
            middle = middle.setScale(Money.SCALE, Money.ROUNDING),
            lower = middle.subtract(offset, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING),
        )
    }

    override fun value(): BigDecimal? = bands()?.middle
}
