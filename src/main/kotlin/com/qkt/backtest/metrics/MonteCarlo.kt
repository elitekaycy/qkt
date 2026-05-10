package com.qkt.backtest.metrics

import com.qkt.backtest.EquityFanPoint
import com.qkt.backtest.MonteCarloSummary
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.random.Random

object MonteCarlo {
    private val mc = MathContext(16, RoundingMode.HALF_EVEN)

    fun run(
        tradeReturns: List<BigDecimal>,
        startingEquity: BigDecimal,
        simulations: Int,
        seed: Long,
    ): MonteCarloSummary {
        require(simulations > 0) { "simulations must be > 0: $simulations" }
        require(tradeReturns.isNotEmpty()) { "tradeReturns must not be empty" }
        val rng = Random(seed)
        val n = tradeReturns.size
        val curves = Array(simulations) { Array(n) { BigDecimal.ZERO } }
        val finalEquities = Array(simulations) { BigDecimal.ZERO }
        val maxDrawdowns = Array(simulations) { BigDecimal.ZERO }

        for (sim in 0 until simulations) {
            var equity = startingEquity
            var peak = startingEquity
            var maxDd = BigDecimal.ZERO
            for (i in 0 until n) {
                val pick = rng.nextInt(n)
                equity = equity.add(tradeReturns[pick])
                curves[sim][i] = equity
                if (equity > peak) peak = equity
                if (peak.signum() != 0) {
                    val dd = equity.subtract(peak).divide(peak, mc)
                    if (dd < maxDd) maxDd = dd
                }
            }
            finalEquities[sim] = equity
            maxDrawdowns[sim] = maxDd
        }

        val sortedFinals = finalEquities.toList().sorted()
        val sortedDds = maxDrawdowns.toList().sorted()

        val fan =
            (0 until n).map { i ->
                val column = (0 until simulations).map { curves[it][i] }.sorted()
                EquityFanPoint(
                    tradeIndex = i,
                    p5 = column.percentile(0.05),
                    p25 = column.percentile(0.25),
                    p50 = column.percentile(0.50),
                    p75 = column.percentile(0.75),
                    p95 = column.percentile(0.95),
                )
            }

        val negativeCount = finalEquities.count { it.signum() < 0 }
        val probNeg = BigDecimal(negativeCount).divide(BigDecimal(simulations), mc)

        return MonteCarloSummary(
            simulations = simulations,
            finalEquityP5 = sortedFinals.percentile(0.05),
            finalEquityP25 = sortedFinals.percentile(0.25),
            finalEquityP50 = sortedFinals.percentile(0.50),
            finalEquityP75 = sortedFinals.percentile(0.75),
            finalEquityP95 = sortedFinals.percentile(0.95),
            maxDrawdownP5 = sortedDds.percentile(0.05),
            maxDrawdownP95 = sortedDds.percentile(0.95),
            probabilityNegativeFinal = probNeg,
            equityFanByTradeIndex = fan,
        )
    }

    private fun List<BigDecimal>.percentile(p: Double): BigDecimal {
        if (isEmpty()) return BigDecimal.ZERO
        val idx = (p * (size - 1)).toInt().coerceIn(0, size - 1)
        return this[idx]
    }
}
