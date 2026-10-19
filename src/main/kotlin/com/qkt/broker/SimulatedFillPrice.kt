package com.qkt.broker

import com.qkt.common.Side
import com.qkt.instrument.InstrumentMeta
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The side-adjusted fair fill price the mt5-sim broker fills at: ask for BUY, bid for SELL. The quotes
 * are the tick's own unless the instrument states its venue's spread ([InstrumentMeta.spreadPoints],
 * [InstrumentMeta.minSpreadPoints]); feeds that carry only mid get [syntheticSpreadPoints] venue
 * points synthesised around it when the instrument states none. O(1) per fill. Each price it gives is
 * tallied by the kind of quote it rests on ([quotes]), so a run can say what its fills used (#1437).
 */
internal class SimulatedFillPrice(
    val syntheticSpreadPoints: Int,
) {
    private var recorded = 0L
    private var venueSpread = 0L
    private var synthetic = 0L

    /** How many prices so far came from each kind of quote. */
    fun quotes(): FillQuotes = FillQuotes(recorded, venueSpread, synthetic)

    /**
     * The fill price for [side] on [tick] (or [fallback] as mid when there is no tick). Null if there
     * is no usable price.
     */
    fun of(
        side: Side,
        tick: Tick?,
        fallback: BigDecimal?,
        meta: InstrumentMeta,
    ): BigDecimal? {
        val bid = tick?.bid
        val ask = tick?.ask
        if (bid != null && ask != null) {
            val points = quotedSpreadPoints(bid, ask, meta)
            if (points == null) {
                recorded++
                return if (side == Side.BUY) ask else bid
            }
            venueSpread++
            return around(bid.add(ask).divide(TWO), points, side, meta)
        }
        val mid = tick?.price ?: fallback ?: return null
        val stated = meta.spreadPoints ?: meta.minSpreadPoints?.takeIf { it > syntheticSpreadPoints }
        if (stated != null) venueSpread++ else synthetic++
        val points = stated ?: syntheticSpreadPoints
        if (points == 0) return mid
        return around(mid, points, side, meta)
    }

    /** The spread to re-centre a two-sided tick at, or null to fill at its own quotes. */
    private fun quotedSpreadPoints(
        bid: BigDecimal,
        ask: BigDecimal,
        meta: InstrumentMeta,
    ): Int? {
        meta.spreadPoints?.let { return it }
        val floor = meta.minSpreadPoints ?: return null
        return if (ask.subtract(bid) < meta.pointSize.multiply(BigDecimal(floor))) floor else null
    }

    private fun around(
        mid: BigDecimal,
        points: Int,
        side: Side,
        meta: InstrumentMeta,
    ): BigDecimal {
        val halfSpread =
            meta.pointSize
                .multiply(BigDecimal(points))
                .divide(TWO, meta.digits + 2, RoundingMode.HALF_EVEN)
        return if (side == Side.BUY) mid.add(halfSpread) else mid.subtract(halfSpread)
    }

    private companion object {
        val TWO = BigDecimal(2)
    }
}
