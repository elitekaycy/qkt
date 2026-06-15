package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import kotlin.math.exp
import kotlin.math.ln

/**
 * Rolling composite-index state for one equal-weight basket. Turns the constituents'
 * same-window closed bars into a single synthetic candle.
 *
 * The composite is an equal-weight log-return index, base 100: each window's index return
 * is the simple average of the constituents' log returns, compounded onto the previous
 * index. So a window where both constituents rise 1% moves the index by ~1%; a window where
 * one rises 1% and the other falls 1% leaves it ~unchanged.
 *
 * `I(t) = I(t-1) * exp( (1/N) Σ ln(p_i(t)/p_i(t-1)) )`, with `I(t0) = 100`.
 *
 * The first aligned window only establishes the baseline (index = 100, no return yet), so
 * [onAligned] returns `null` for it; the first real return — and the first candle — lands on
 * the second aligned window. e.g. constituents at 1.00 then 1.01 (a +1% window) yield a
 * candle with `open = 100`, `close ≈ 101`.
 *
 * One instance per basket; not thread-safe (the hub drives a single basket from one
 * sync-group callback).
 */
class BasketCompositor(
    private val symbol: String,
    private val constituents: List<String>,
) {
    init {
        require(symbol.isNotBlank()) { "BasketCompositor.symbol must not be blank" }
        require(constituents.size >= 2) {
            "BasketCompositor needs at least 2 constituents, got ${constituents.size}"
        }
    }

    private val base = BigDecimal(100)
    private var prevIndex: BigDecimal? = null
    private val prevClose: MutableMap<String, BigDecimal> = mutableMapOf()

    /**
     * Fold one aligned window into the index and return the basket's candle for it, or
     * `null` if this is the first aligned window (baseline only — no return computed yet).
     *
     * [bars] must hold a closed bar for every constituent, keyed by constituent alias
     * (exactly what the constituent sync group delivers).
     */
    fun onAligned(bars: Map<String, Candle>): Candle? {
        val closes = constituents.associateWith { alias -> bars.getValue(alias).close }
        val previous = prevIndex
        if (previous == null) {
            prevIndex = base
            prevClose.putAll(closes)
            return null
        }

        var sumLogReturns = BigDecimal.ZERO
        for (alias in constituents) {
            val now = closes.getValue(alias)
            val before = prevClose.getValue(alias)
            val ratio = now.divide(before, Money.CONTEXT)
            val logReturn = BigDecimal(ln(ratio.toDouble())).round(Money.CONTEXT)
            sumLogReturns = sumLogReturns.add(logReturn, Money.CONTEXT)
        }
        val avgReturn = sumLogReturns.divide(BigDecimal(constituents.size), Money.CONTEXT)
        val index =
            previous
                .multiply(BigDecimal(exp(avgReturn.toDouble())).round(Money.CONTEXT), Money.CONTEXT)
                .setScale(Money.SCALE, Money.ROUNDING)

        prevIndex = index
        prevClose.putAll(closes)

        val window = bars.getValue(constituents.first())
        return Candle(
            symbol = symbol,
            open = previous.setScale(Money.SCALE, Money.ROUNDING),
            high = previous.max(index).setScale(Money.SCALE, Money.ROUNDING),
            low = previous.min(index).setScale(Money.SCALE, Money.ROUNDING),
            close = index,
            volume = BigDecimal.ZERO,
            startTime = window.startTime,
            endTime = window.endTime,
        )
    }
}
