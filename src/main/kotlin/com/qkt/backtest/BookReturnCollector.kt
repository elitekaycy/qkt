package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.common.Money
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import java.math.BigDecimal
import java.math.MathContext

/**
 * Online cross-strategy return statistics for a book of strategies sharing one account.
 *
 * Subscribes to the same sample cadence as [EquityCurveCollector]. On each sample it folds every
 * strategy's return-since-last-sample into running sums (per strategy, and per ordered pair), so
 * pairwise return correlation and each strategy's percent-contribution-to-risk recover at the end
 * without retaining the full return series — constant memory in the number of samples, O(strategies^2)
 * in space.
 *
 * Returns are measured on a constant capital base ([startingBalance]): strategy i's return at a
 * sample is `(pnl_i_now - pnl_i_prev) / startingBalance`. The book return is then exactly the sum of
 * strategy returns, which makes the risk-contribution decomposition sum to 1. PnL (not equity) is the
 * basis because the engine anchors every strategy at the full starting balance, so equity-based
 * shares would not sum cleanly.
 *
 * It also tracks the book's deepest peak-to-trough drawdown window and the per-strategy PnL at that
 * window's peak and trough, so a drawdown can be attributed to the strategies that drove it.
 */
class BookReturnCollector(
    cadence: SampleCadence,
    bus: EventBus,
    private val pnl: PnLProvider,
    private val strategyPnL: StrategyPnL,
    private val strategyIds: List<String>,
    private val startingBalance: BigDecimal,
) {
    private val n = strategyIds.size
    private var count = 0
    private val prevPnl = HashMap<String, BigDecimal>()
    private val sumR = HashMap<String, BigDecimal>().apply { strategyIds.forEach { put(it, Money.ZERO) } }
    private val sumR2 = HashMap<String, BigDecimal>().apply { strategyIds.forEach { put(it, Money.ZERO) } }
    private val sumRiRbook = HashMap<String, BigDecimal>().apply { strategyIds.forEach { put(it, Money.ZERO) } }
    private val sumCross = HashMap<Pair<String, String>, BigDecimal>()
    private var sumRBook = Money.ZERO
    private var sumRBook2 = Money.ZERO

    private var bookPeakPnl: BigDecimal? = null
    private var pnlAtRunningPeak: Map<String, BigDecimal> = emptyMap()
    private var maxDd = Money.ZERO
    private var peakBookPnl = Money.ZERO
    private var troughBookPnl = Money.ZERO
    private var pnlAtPeak: Map<String, BigDecimal> = emptyMap()
    private var pnlAtTrough: Map<String, BigDecimal> = emptyMap()

    init {
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                sumCross[strategyIds[i] to strategyIds[j]] = Money.ZERO
            }
        }
        when (cadence) {
            SampleCadence.CANDLE_CLOSE -> bus.subscribe<CandleEvent> { sample() }
            SampleCadence.TICK -> bus.subscribe<TickEvent> { sample() }
            SampleCadence.FILL -> bus.subscribe<BrokerEvent.OrderFilled> { sample() }
        }
    }

    private fun sample() {
        if (n < 2 || startingBalance.signum() <= 0) return
        val cur = strategyIds.associateWith { strategyPnL.totalFor(it) }
        val bookPnl = pnl.realizedTotal().add(pnl.unrealizedTotal())
        if (prevPnl.isNotEmpty()) foldReturns(cur)
        trackDrawdown(cur, bookPnl)
        for (id in strategyIds) prevPnl[id] = cur.getValue(id)
    }

    private fun foldReturns(cur: Map<String, BigDecimal>) {
        val r = HashMap<String, BigDecimal>(n)
        var rBook = Money.ZERO
        for (id in strategyIds) {
            val ri = cur.getValue(id).subtract(prevPnl.getValue(id)).divide(startingBalance, Money.CONTEXT)
            r[id] = ri
            rBook = rBook.add(ri)
            sumR[id] = sumR.getValue(id).add(ri)
            sumR2[id] = sumR2.getValue(id).add(ri.multiply(ri, Money.CONTEXT))
        }
        sumRBook = sumRBook.add(rBook)
        sumRBook2 = sumRBook2.add(rBook.multiply(rBook, Money.CONTEXT))
        for (id in strategyIds) {
            sumRiRbook[id] = sumRiRbook.getValue(id).add(r.getValue(id).multiply(rBook, Money.CONTEXT))
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val key = strategyIds[i] to strategyIds[j]
                val prod = r.getValue(strategyIds[i]).multiply(r.getValue(strategyIds[j]), Money.CONTEXT)
                sumCross[key] = sumCross.getValue(key).add(prod)
            }
        }
        count++
    }

    private fun trackDrawdown(
        cur: Map<String, BigDecimal>,
        bookPnl: BigDecimal,
    ) {
        val peak = bookPeakPnl
        if (peak == null || bookPnl > peak) {
            bookPeakPnl = bookPnl
            pnlAtRunningPeak = cur
        } else if (peak.subtract(bookPnl) > maxDd) {
            maxDd = peak.subtract(bookPnl)
            peakBookPnl = peak
            troughBookPnl = bookPnl
            pnlAtPeak = pnlAtRunningPeak
            pnlAtTrough = cur
        }
    }

    /** Snapshot the analytics, or null when there is nothing cross-strategy to say (< 2 strategies). */
    fun result(): BookAnalytics? {
        if (n < 2 || count < 2) return null
        val varBook = variance(sumRBook, sumRBook2)
        val varI = strategyIds.associateWith { variance(sumR.getValue(it), sumR2.getValue(it)) }

        val correlation =
            buildList {
                for (i in 0 until n) {
                    for (j in i + 1 until n) {
                        val a = strategyIds[i]
                        val b = strategyIds[j]
                        val cov = covariance(sumCross.getValue(a to b), sumR.getValue(a), sumR.getValue(b))
                        val denom = sd(varI.getValue(a)).multiply(sd(varI.getValue(b)), Money.CONTEXT)
                        val corr = if (denom.signum() == 0) Money.ZERO else scaled(cov.divide(denom, Money.CONTEXT))
                        add(CorrelationPair(a, b, corr))
                    }
                }
            }

        val riskContribution =
            strategyIds.associateWith { id ->
                val cov = covariance(sumRiRbook.getValue(id), sumR.getValue(id), sumRBook)
                if (varBook.signum() == 0) Money.ZERO else scaled(cov.divide(varBook, Money.CONTEXT))
            }

        val bookTotal = pnl.realizedTotal().add(pnl.unrealizedTotal())
        val contributionToReturn =
            strategyIds.associateWith { id ->
                if (bookTotal.signum() == 0) {
                    Money.ZERO
                } else {
                    scaled(strategyPnL.totalFor(id).divide(bookTotal, Money.CONTEXT))
                }
            }

        val bookDdMove = troughBookPnl.subtract(peakBookPnl)
        val drawdownContribution =
            strategyIds.associateWith { id ->
                if (bookDdMove.signum() == 0) {
                    Money.ZERO
                } else {
                    val move = (pnlAtTrough[id] ?: Money.ZERO).subtract(pnlAtPeak[id] ?: Money.ZERO)
                    scaled(move.divide(bookDdMove, Money.CONTEXT))
                }
            }

        return BookAnalytics(contributionToReturn, correlation, riskContribution, drawdownContribution)
    }

    private fun variance(
        sumX: BigDecimal,
        sumX2: BigDecimal,
    ): BigDecimal {
        val mean = sumX.divide(BigDecimal(count), Money.CONTEXT)
        return sumX2.subtract(mean.multiply(sumX, Money.CONTEXT)).divide(BigDecimal(count - 1), Money.CONTEXT)
    }

    private fun covariance(
        sumXY: BigDecimal,
        sumX: BigDecimal,
        sumY: BigDecimal,
    ): BigDecimal {
        val meanX = sumX.divide(BigDecimal(count), Money.CONTEXT)
        return sumXY.subtract(meanX.multiply(sumY, Money.CONTEXT)).divide(BigDecimal(count - 1), Money.CONTEXT)
    }

    private fun sd(variance: BigDecimal): BigDecimal =
        if (variance.signum() <= 0) Money.ZERO else variance.sqrt(MathContext.DECIMAL64)

    private fun scaled(v: BigDecimal): BigDecimal = v.setScale(Money.SCALE, Money.ROUNDING)
}
