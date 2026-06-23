package com.qkt.broker

import com.qkt.common.Side
import com.qkt.instrument.InstrumentMeta
import java.math.BigDecimal
import kotlin.random.Random

/**
 * Pluggable slippage adjustment applied to fill prices in [MT5BrokerSimulator].
 *
 * Slippage is the difference between the price a strategy intended to fill at and the
 * price the venue actually filled at — caused by latency, volatility, and depth. Real
 * MT5 fills experience slippage; backtest fills against [PaperBroker] never have.
 *
 * Models are deterministic given their inputs so the simulator stays reproducible: a
 * fixed-pip slippage always returns the same adjustment; a randomized slippage uses a
 * seeded RNG so the same seed yields the same series.
 */
interface SlippageModel {
    /**
     * Returns the price the venue actually fills at, given the fair fill price
     * [fillPrice] (already side-adjusted to bid/ask) and the side. Implementations
     * shift adverse to the [side] (BUY pays more, SELL receives less) by some
     * amount they choose.
     */
    fun adjust(
        fillPrice: BigDecimal,
        side: Side,
        meta: InstrumentMeta,
    ): BigDecimal
}

/** No slippage applied. Default for deterministic backtests. */
object ZeroSlippage : SlippageModel {
    override fun adjust(
        fillPrice: BigDecimal,
        side: Side,
        meta: InstrumentMeta,
    ): BigDecimal = fillPrice
}

/**
 * Constant slippage in venue points (so 2 points on XAUUSD with pointSize=0.001 = 0.002).
 * Adverse to the side: BUY pays `+points × pointSize`, SELL receives `-points × pointSize`.
 *
 * Useful for stress-testing a strategy's expected return after realistic execution cost.
 */
class FixedPointsSlippage(
    private val points: Int,
) : SlippageModel {
    init {
        require(points >= 0) { "FixedPointsSlippage.points must be >= 0: $points" }
    }

    override fun adjust(
        fillPrice: BigDecimal,
        side: Side,
        meta: InstrumentMeta,
    ): BigDecimal = adversePoints(fillPrice, side, points, meta.pointSize)
}

/**
 * Seeded-random slippage uniformly in `[0, maxPoints]` venue points, applied adverse
 * to the side. Deterministic given [seed].
 */
class UniformRandomSlippage(
    private val maxPoints: Int,
    private val seed: Long,
) : SlippageModel {
    init {
        require(maxPoints >= 0) { "UniformRandomSlippage.maxPoints must be >= 0: $maxPoints" }
    }

    private val rng = Random(seed)

    override fun adjust(
        fillPrice: BigDecimal,
        side: Side,
        meta: InstrumentMeta,
    ): BigDecimal {
        if (maxPoints == 0) return fillPrice
        return adversePoints(fillPrice, side, rng.nextInt(0, maxPoints + 1), meta.pointSize)
    }
}

/**
 * Per-instrument slippage: shifts the fill adverse to the side by the traded instrument's own
 * [InstrumentMeta.slippagePoints] (in venue points, each [InstrumentMeta.pointSize] wide). Lets
 * each symbol carry its realistic execution slip in `instruments.yaml`, the same way
 * [InstrumentMeta.commissionPerLot] carries its commission — a symbol left at zero points fills
 * with no slippage, so this model is a no-op until values are set.
 *
 * e.g. slippagePoints=5 on a 0.001-pointSize symbol: a BUY filling at 2000.000 fills at 2000.005.
 */
object InstrumentSlippage : SlippageModel {
    override fun adjust(
        fillPrice: BigDecimal,
        side: Side,
        meta: InstrumentMeta,
    ): BigDecimal = adversePoints(fillPrice, side, meta.slippagePoints, meta.pointSize)
}

/** Shift [fillPrice] adverse to [side] by [points] venue points, each [pointSize] wide. */
private fun adversePoints(
    fillPrice: BigDecimal,
    side: Side,
    points: Int,
    pointSize: BigDecimal,
): BigDecimal {
    if (points <= 0) return fillPrice
    val delta = pointSize.multiply(BigDecimal(points))
    return if (side == Side.BUY) fillPrice.add(delta) else fillPrice.subtract(delta)
}
