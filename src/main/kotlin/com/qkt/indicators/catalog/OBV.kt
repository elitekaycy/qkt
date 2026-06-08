package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * On-Balance Volume — a running total that adds the candle's volume on an up-close and subtracts
 * it on a down-close, leaving it unchanged on a flat close.
 *
 * The idea: volume should confirm price. A rising OBV alongside rising price says the move has
 * participation; OBV diverging from price (e.g. price makes a new high but OBV doesn't) warns the
 * move is thinning out.
 *
 * Cumulative, so it takes no period and starts at zero on the first candle. Common use: trade in
 * the direction of OBV's trend, or flag price/OBV divergence as a reversal cue.
 */
class OBV : Indicator<Candle> {
    private var prevClose: BigDecimal? = null
    private var obv: BigDecimal = BigDecimal.ZERO
    private var seen: Boolean = false

    override val warmupBars: Int = 1

    override val isReady: Boolean
        get() = seen

    override fun update(input: Candle) {
        val prev = prevClose
        if (prev != null) {
            when (input.close.compareTo(prev)) {
                1 -> obv = obv.add(input.volume, Money.CONTEXT)
                -1 -> obv = obv.subtract(input.volume, Money.CONTEXT)
            }
        }
        prevClose = input.close
        seen = true
    }

    override fun value(): BigDecimal? = if (seen) obv.setScale(Money.SCALE, Money.ROUNDING) else null
}
