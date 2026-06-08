package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/** Directional movement lines: +DI, -DI, and the ADX trend-strength reading. */
data class ADXLines(
    val plusDi: BigDecimal,
    val minusDi: BigDecimal,
    val adx: BigDecimal,
)

/**
 * Average Directional Index (Wilder) — a [0, 100] gauge of trend *strength* (not direction),
 * with its +DI / -DI components giving direction.
 *
 * Per bar: +DM / -DM (directional movement) and TR (true range) are Wilder-smoothed over
 * [period]; +DI = 100·smoothed(+DM)/smoothed(TR), -DI likewise; DX = 100·|+DI−-DI|/(+DI+-DI);
 * ADX = Wilder-smoothed DX. A rising ADX above ~25 signals a strong trend (up if +DI > -DI, down
 * if -DI > +DI); a low ADX means range-bound chop.
 *
 * Multi-output: the DSL exposes `PLUS_DI`, `MINUS_DI`, `ADX`. Returns null (via [lines]) until
 * warmed up.
 */
class ADX(
    private val period: Int,
) : Indicator<Candle> {
    init {
        require(period > 0) { "ADX.period must be > 0: $period" }
    }

    private var prevHigh: BigDecimal? = null
    private var prevLow: BigDecimal? = null
    private var prevClose: BigDecimal? = null

    private val plusDmSmooth = Wilder(period)
    private val minusDmSmooth = Wilder(period)
    private val trSmooth = Wilder(period)
    private val dxSmooth = Wilder(period)

    private val hundred = BigDecimal(100)

    override val warmupBars: Int = 2 * period
    override val isReady: Boolean
        get() = dxSmooth.value() != null

    override fun update(input: Candle) {
        val ph = prevHigh
        val pl = prevLow
        val pc = prevClose
        prevHigh = input.high
        prevLow = input.low
        prevClose = input.close
        if (ph == null || pl == null || pc == null) return

        val upMove = input.high.subtract(ph, Money.CONTEXT)
        val downMove = pl.subtract(input.low, Money.CONTEXT)
        val plusDm = if (upMove > downMove && upMove.signum() > 0) upMove else BigDecimal.ZERO
        val minusDm = if (downMove > upMove && downMove.signum() > 0) downMove else BigDecimal.ZERO
        val tr =
            input.high
                .subtract(input.low, Money.CONTEXT)
                .max(input.high.subtract(pc, Money.CONTEXT).abs())
                .max(input.low.subtract(pc, Money.CONTEXT).abs())

        plusDmSmooth.update(plusDm)
        minusDmSmooth.update(minusDm)
        trSmooth.update(tr)

        val sTr = trSmooth.value() ?: return
        val sPlus = plusDmSmooth.value() ?: return
        val sMinus = minusDmSmooth.value() ?: return
        if (sTr.signum() == 0) return
        val plusDi = hundred.multiply(sPlus, Money.CONTEXT).divide(sTr, Money.CONTEXT)
        val minusDi = hundred.multiply(sMinus, Money.CONTEXT).divide(sTr, Money.CONTEXT)
        val diSum = plusDi.add(minusDi, Money.CONTEXT)
        if (diSum.signum() == 0) return
        val dx =
            hundred
                .multiply(plusDi.subtract(minusDi, Money.CONTEXT).abs(), Money.CONTEXT)
                .divide(diSum, Money.CONTEXT)
        dxSmooth.update(dx)
    }

    /** The +DI, -DI, and ADX lines, or null until all three are computable. */
    fun lines(): ADXLines? {
        val sTr = trSmooth.value() ?: return null
        val sPlus = plusDmSmooth.value() ?: return null
        val sMinus = minusDmSmooth.value() ?: return null
        val adx = dxSmooth.value() ?: return null
        if (sTr.signum() == 0) return null
        val plusDi = hundred.multiply(sPlus, Money.CONTEXT).divide(sTr, Money.CONTEXT)
        val minusDi = hundred.multiply(sMinus, Money.CONTEXT).divide(sTr, Money.CONTEXT)
        return ADXLines(
            plusDi = plusDi.setScale(Money.SCALE, Money.ROUNDING),
            minusDi = minusDi.setScale(Money.SCALE, Money.ROUNDING),
            adx = adx.setScale(Money.SCALE, Money.ROUNDING),
        )
    }

    override fun value(): BigDecimal? = lines()?.adx

    /** Wilder's averaging smoother: seed with a [period]-value mean, then `avg += (x − avg)/period`. */
    private class Wilder(
        private val period: Int,
    ) {
        private val seed: MutableList<BigDecimal> = ArrayList(period)
        private var avg: BigDecimal? = null
        private val periodBd = BigDecimal(period)
        private val periodMinusOne = BigDecimal(period - 1)

        fun value(): BigDecimal? = avg

        fun update(v: BigDecimal) {
            val a = avg
            if (a == null) {
                seed.add(v)
                if (seed.size == period) {
                    var sum = BigDecimal.ZERO
                    for (x in seed) sum = sum.add(x, Money.CONTEXT)
                    avg = sum.divide(periodBd, Money.CONTEXT)
                    seed.clear()
                }
            } else {
                avg =
                    a
                        .multiply(periodMinusOne, Money.CONTEXT)
                        .add(v, Money.CONTEXT)
                        .divide(periodBd, Money.CONTEXT)
            }
        }
    }
}
