package com.qkt.risk.book

import com.qkt.common.Money
import java.math.BigDecimal

/**
 * The book-risk brain. Fed a [BookSnapshot] each sample (by the measurement monitor), it refreshes an
 * immutable [BookRiskState] the pre-trade gate and order sizing read. Every output is a deterministic
 * function of the snapshots seen plus config, so the same controller produces the same decisions in
 * backtest and live.
 *
 * It owns three controls: exposure-limit state, the drawdown de-risk factor (via [DeRiskLadder]), and
 * dynamic allocation weights (inverse-vol / ERC + optional vol-targeting) recomputed on the rebalance
 * cadence from the rolling cross-strategy covariance.
 */
class BookRiskController(
    private val config: BookRiskConfig,
    private val capital: BigDecimal,
    private val annualization: BigDecimal = BigDecimal("252"),
) {
    private val ladder = config.deRisk?.let { DeRiskLadder(it.ladder) }
    private val allocation = config.allocation
    private var peakEquity = capital

    // Online cross-strategy return covariance (constant capital base), for dynamic allocation.
    private var ids: List<String> = emptyList()
    private val prevPnl = HashMap<String, BigDecimal>()
    private val sumR = HashMap<String, BigDecimal>()
    private val sumR2 = HashMap<String, BigDecimal>()
    private val sumCross = HashMap<Pair<String, String>, BigDecimal>()
    private var count = 0
    private var barCount = 0
    private var weights: Map<String, BigDecimal> = emptyMap()

    @Volatile
    private var current: BookRiskState = BookRiskState(capital, Money.ZERO, emptyMap(), config.limits)

    fun onSample(snapshot: BookSnapshot) {
        if (snapshot.bookEquity > peakEquity) peakEquity = snapshot.bookEquity
        val drawdown =
            if (peakEquity.signum() > 0) {
                peakEquity.subtract(snapshot.bookEquity).divide(peakEquity, Money.CONTEXT).max(Money.ZERO)
            } else {
                Money.ZERO
            }
        val factor = ladder?.factorFor(drawdown) ?: BigDecimal.ONE

        if (allocation != null && capital.signum() > 0) {
            foldReturns(snapshot.perStrategyPnl)
            barCount += 1
            val every = maxOf(1, allocation.rebalanceEveryBars)
            if (count >= 2 && barCount % every == 0) weights = computeWeights()
        }

        current =
            BookRiskState(
                capital = capital,
                grossExposure = snapshot.exposure.gross,
                perSymbolNet = snapshot.exposure.perSymbolNet,
                limits = config.limits,
                deRiskFactor = factor,
                allocationWeights = weights,
            )
    }

    fun state(): BookRiskState = current

    private fun foldReturns(perStrategyPnl: Map<String, BigDecimal>) {
        if (ids.isEmpty()) {
            ids = perStrategyPnl.keys.sorted()
            for (id in ids) {
                sumR[id] = Money.ZERO
                sumR2[id] = Money.ZERO
            }
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) sumCross[ids[i] to ids[j]] = Money.ZERO
            }
        }
        if (prevPnl.isNotEmpty()) {
            val r = HashMap<String, BigDecimal>(ids.size)
            for (id in ids) {
                val cur = perStrategyPnl[id] ?: Money.ZERO
                val ri = cur.subtract(prevPnl.getValue(id)).divide(capital, Money.CONTEXT)
                r[id] = ri
                sumR[id] = sumR.getValue(id).add(ri)
                sumR2[id] = sumR2.getValue(id).add(ri.multiply(ri, Money.CONTEXT))
            }
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    val key = ids[i] to ids[j]
                    val prod = r.getValue(ids[i]).multiply(r.getValue(ids[j]), Money.CONTEXT)
                    sumCross[key] = sumCross.getValue(key).add(prod)
                }
            }
            count += 1
        }
        for (id in ids) prevPnl[id] = perStrategyPnl[id] ?: Money.ZERO
    }

    private fun cov(
        i: String,
        j: String,
    ): BigDecimal {
        val n = BigDecimal(count)
        val meanI = sumR.getValue(i).divide(n, Money.CONTEXT)
        val sumXY = if (i == j) sumR2.getValue(i) else (sumCross[i to j] ?: sumCross.getValue(j to i))
        return sumXY
            .subtract(
                meanI.multiply(sumR.getValue(j), Money.CONTEXT),
            ).divide(BigDecimal(count - 1), Money.CONTEXT)
    }

    private fun computeWeights(): Map<String, BigDecimal> {
        val a = allocation ?: return emptyMap()
        val variances = ids.associateWith { cov(it, it) }
        val raw =
            when (a.method) {
                AllocationMethod.FIXED -> equalWeights(ids)
                AllocationMethod.INVERSE_VOL -> inverseVol(variances)
                AllocationMethod.ERC -> erc(ids, ::cov)
            }
        // Express weights as a tilt around 1.0 (FIXED -> all 1.0) so they overlay the static
        // CAPITAL x WEIGHT rather than replace it.
        val n = BigDecimal(ids.size)
        val tilt = raw.mapValues { (_, w) -> w.multiply(n, Money.CONTEXT) }
        val targeted =
            if (a.targetVol != null) {
                volTarget(tilt, ids, ::cov, annualization, a.targetVol, a.maxLeverage)
            } else {
                tilt
            }
        return targeted.mapValues { it.value.setScale(Money.SCALE, Money.ROUNDING) }
    }
}
