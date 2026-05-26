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
    ): BigDecimal {
        val delta = meta.pointSize.multiply(BigDecimal(points))
        return if (side == Side.BUY) fillPrice.add(delta) else fillPrice.subtract(delta)
    }
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
        val drawn = rng.nextInt(0, maxPoints + 1)
        val delta = meta.pointSize.multiply(BigDecimal(drawn))
        return if (side == Side.BUY) fillPrice.add(delta) else fillPrice.subtract(delta)
    }
}
