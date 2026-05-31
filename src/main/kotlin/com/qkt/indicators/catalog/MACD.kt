package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/** Three values from a MACD reading: the line, its smoothed signal line, and the histogram (line − signal). */
data class MACDLines(
    val macd: BigDecimal,
    val signal: BigDecimal,
    val histogram: BigDecimal,
)

/**
 * MACD — a momentum gauge built from two [EMA]s, one fast and one slow. When the
 * fast one is above the slow one, recent momentum is up; when below, down.
 *
 * Three things come out:
 *  - **macd line** — `EMA(fast) − EMA(slow)`. Positive = bullish momentum.
 *  - **signal line** — an EMA of the macd line, smoother.
 *  - **histogram** — `macd − signal`. Sign change is the classic entry trigger.
 *
 * e.g. histogram crosses from negative to positive → fast EMA pulling away from
 * slow EMA → "MACD bullish crossover."
 *
 * Defaults: fast=12, slow=26, signal=9 — the original 1979 recipe. Shorter
 * params react faster but generate more false signals.
 */
class MACD(
    private val fast: Int = 12,
    private val slow: Int = 26,
    private val signal: Int = 9,
) : Indicator<BigDecimal> {
    init {
        require(fast > 0) { "fast must be > 0: $fast" }
        require(slow > fast) { "slow must be > fast: slow=$slow, fast=$fast" }
        require(signal > 0) { "signal must be > 0: $signal" }
    }

    private val fastEma = EMA(fast)
    private val slowEma = EMA(slow)
    private val signalEma = EMA(signal)

    override val warmupBars: Int = slow + signal - 1

    override val isReady: Boolean
        get() = signalEma.isReady

    override fun update(input: BigDecimal) {
        fastEma.update(input)
        slowEma.update(input)
        if (fastEma.isReady && slowEma.isReady) {
            val macdLine = fastEma.value()!!.subtract(slowEma.value()!!, Money.CONTEXT)
            signalEma.update(macdLine)
        }
    }

    override fun value(): BigDecimal? = lines()?.macd

    fun lines(): MACDLines? {
        if (!fastEma.isReady || !slowEma.isReady || !signalEma.isReady) return null
        val macdLine = fastEma.value()!!.subtract(slowEma.value()!!, Money.CONTEXT)
        val sig = signalEma.value()!!
        val hist = macdLine.subtract(sig, Money.CONTEXT)
        return MACDLines(
            macd = macdLine.setScale(Money.SCALE, Money.ROUNDING),
            signal = sig.setScale(Money.SCALE, Money.ROUNDING),
            histogram = hist.setScale(Money.SCALE, Money.ROUNDING),
        )
    }
}
