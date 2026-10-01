package com.qkt.broker

import com.qkt.common.Side
import com.qkt.instrument.InstrumentMeta
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The side-adjusted fair fill price the mt5-sim broker fills at: ask for BUY, bid for SELL. Feeds that
 * carry only mid get a spread of [syntheticSpreadPoints] venue points synthesised around it.
 */
internal class SimulatedFillPrice(
    private val syntheticSpreadPoints: Int,
) {
    /**
     * Prefers [tick]`.ask`/`.bid` when present; otherwise synthesises spread around [fallback] (or
     * [tick]`.price`) using `meta.pointSize × syntheticSpreadPoints`. Null if there is no usable price.
     */
    fun of(
        side: Side,
        tick: Tick?,
        fallback: BigDecimal?,
        meta: InstrumentMeta,
    ): BigDecimal? {
        val bid = tick?.bid
        val ask = tick?.ask
        if (bid != null && ask != null) return if (side == Side.BUY) ask else bid
        val mid = tick?.price ?: fallback ?: return null
        if (syntheticSpreadPoints == 0) return mid
        val halfSpread =
            meta.pointSize
                .multiply(BigDecimal(syntheticSpreadPoints))
                .divide(BigDecimal(2), meta.digits + 2, RoundingMode.HALF_EVEN)
        return if (side == Side.BUY) mid.add(halfSpread) else mid.subtract(halfSpread)
    }
}
