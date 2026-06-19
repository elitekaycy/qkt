package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.common.Money
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.risk.book.BookStateSource
import java.math.BigDecimal
import java.math.MathContext

/**
 * Accumulates the book-risk measurement series for a portfolio run. Subscribes to the same sample
 * cadence as [EquityCurveCollector]; on each sample it pulls a [com.qkt.risk.book.BookSnapshot] from
 * the [source], decimates the exposure/equity series, tracks peak gross/net exposure, and folds the
 * book return (Δ equity / starting balance) into an online variance for annualized book volatility.
 *
 * Null result on single-strategy runs or no capital basis — there is no "book" to measure.
 */
class BookRiskMonitor(
    cadence: SampleCadence,
    bus: EventBus,
    private val source: BookStateSource,
    private val strategyCount: Int,
    private val startingBalance: BigDecimal,
    curveCap: Int = EquityCurveCollector.DEFAULT_CURVE_CAP,
) {
    private val series = DecimatedSeries<BookRiskSample>(curveCap)
    private var prevEquity: BigDecimal? = null
    private var count = 0
    private var sumR = Money.ZERO
    private var sumR2 = Money.ZERO
    private var maxGross = Money.ZERO
    private var maxNet = Money.ZERO

    init {
        when (cadence) {
            SampleCadence.CANDLE_CLOSE -> bus.subscribe<CandleEvent> { sample(it.candle.endTime) }
            SampleCadence.TICK -> bus.subscribe<TickEvent> { sample(it.tick.timestamp) }
            SampleCadence.FILL -> bus.subscribe<BrokerEvent.OrderFilled> { sample(it.timestamp) }
        }
    }

    private fun sample(timestampMs: Long) {
        if (strategyCount < 2 || startingBalance.signum() <= 0) return
        val snap = source.sample(timestampMs)
        series.accept(BookRiskSample(timestampMs, snap.exposure.gross, snap.exposure.net, snap.bookEquity))
        if (snap.exposure.gross > maxGross) maxGross = snap.exposure.gross
        if (snap.exposure.net > maxNet) maxNet = snap.exposure.net
        val prev = prevEquity
        if (prev != null) {
            val r = snap.bookEquity.subtract(prev).divide(startingBalance, Money.CONTEXT)
            sumR = sumR.add(r)
            sumR2 = sumR2.add(r.multiply(r, Money.CONTEXT))
            count++
        }
        prevEquity = snap.bookEquity
    }

    fun result(annualizationFactor: BigDecimal): BookRiskReport? {
        if (strategyCount < 2 || count < 2) return null
        val n = BigDecimal(count)
        val mean = sumR.divide(n, Money.CONTEXT)
        val variance = sumR2.subtract(mean.multiply(sumR, Money.CONTEXT)).divide(BigDecimal(count - 1), Money.CONTEXT)
        val vol =
            if (variance.signum() <= 0) {
                null
            } else {
                variance
                    .sqrt(MathContext.DECIMAL64)
                    .multiply(annualizationFactor.sqrt(MathContext.DECIMAL64), Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            }
        return BookRiskReport(
            series = series.snapshot(),
            bookVol = vol,
            maxGrossExposure = maxGross.setScale(Money.SCALE, Money.ROUNDING),
            maxNetExposure = maxNet.setScale(Money.SCALE, Money.ROUNDING),
        )
    }
}
