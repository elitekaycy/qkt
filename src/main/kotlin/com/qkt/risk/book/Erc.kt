package com.qkt.risk.book

import com.qkt.common.Money
import java.math.BigDecimal
import java.math.MathContext

private val MC = MathContext.DECIMAL64

/** Equal weights summing to 1 over [ids] (the allocation fallback when no risk signal is usable). */
fun equalWeights(ids: Collection<String>): Map<String, BigDecimal> {
    if (ids.isEmpty()) return emptyMap()
    val w = BigDecimal.ONE.divide(BigDecimal(ids.size), MC).setScale(Money.SCALE, Money.ROUNDING)
    return ids.associateWith { w }
}

/** Inverse-volatility weights: w_i proportional to 1/sigma_i, normalized to sum 1. */
fun inverseVol(variances: Map<String, BigDecimal>): Map<String, BigDecimal> {
    if (variances.isEmpty()) return emptyMap()
    val inv =
        variances.mapValues { (_, v) ->
            val sd = if (v.signum() > 0) v.sqrt(MC) else BigDecimal.ZERO
            if (sd.signum() > 0) BigDecimal.ONE.divide(sd, MC) else BigDecimal.ZERO
        }
    val total = inv.values.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
    if (total.signum() <= 0) return equalWeights(variances.keys)
    return inv.mapValues { (_, x) -> x.divide(total, MC).setScale(Money.SCALE, Money.ROUNDING) }
}

/**
 * Equal-risk-contribution (risk-parity) weights via fixed-point iteration on the covariance matrix
 * [cov]: each asset ends up contributing the same share of portfolio variance. Seeded from
 * inverse-vol and updated multiplicatively with sqrt damping; normalized to sum 1 each step. Falls
 * back to inverse-vol if any diagonal variance is non-positive (degenerate matrix).
 *
 * e.g. for an uncorrelated pair with variances 0.04 and 0.01, ERC == inverse-vol (1/3, 2/3).
 */
fun erc(
    ids: List<String>,
    cov: (String, String) -> BigDecimal,
    iterations: Int = 200,
): Map<String, BigDecimal> {
    if (ids.isEmpty()) return emptyMap()
    if (ids.size == 1) return mapOf(ids[0] to BigDecimal.ONE)
    val variances = ids.associateWith { cov(it, it) }
    if (variances.values.any { it.signum() <= 0 }) return inverseVol(variances)

    val w = inverseVol(variances).toMutableMap()
    repeat(iterations) {
        val mrc =
            ids.associateWith { i ->
                ids.fold(BigDecimal.ZERO) { acc, j -> acc.add(cov(i, j).multiply(w.getValue(j), MC)) }
            }
        val rc = ids.associateWith { i -> w.getValue(i).multiply(mrc.getValue(i), MC) }
        val total = rc.values.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
        if (total.signum() <= 0) return inverseVol(variances)
        val target = total.divide(BigDecimal(ids.size), MC)
        for (i in ids) {
            val rci = rc.getValue(i)
            if (rci.signum() > 0) {
                w[i] = w.getValue(i).multiply(target.divide(rci, MC).sqrt(MC), MC)
            }
        }
        val s = w.values.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
        if (s.signum() > 0) for (i in ids) w[i] = w.getValue(i).divide(s, MC)
    }
    return w.mapValues { it.value.setScale(Money.SCALE, Money.ROUNDING) }
}

/**
 * Scale [weights] uniformly so the book's annualized volatility equals [targetVol], capped so gross
 * leverage (sum of absolute weights) does not exceed [maxLeverage]. Per-sample portfolio variance is
 * `wᵀ Σ w`; annualized vol is `sqrt(variance * annualizationFactor)`. Returns [weights] unchanged
 * when the book's vol is zero (nothing to target).
 */
fun volTarget(
    weights: Map<String, BigDecimal>,
    ids: List<String>,
    cov: (String, String) -> BigDecimal,
    annualizationFactor: BigDecimal,
    targetVol: BigDecimal,
    maxLeverage: BigDecimal,
): Map<String, BigDecimal> {
    var variance = BigDecimal.ZERO
    for (i in ids) {
        for (j in ids) {
            variance = variance.add(cov(i, j).multiply(weights.getValue(i), MC).multiply(weights.getValue(j), MC))
        }
    }
    if (variance.signum() <= 0) return weights
    val vol = variance.multiply(annualizationFactor, MC).sqrt(MC)
    if (vol.signum() <= 0) return weights
    var scale = targetVol.divide(vol, MC)
    val gross = weights.values.fold(BigDecimal.ZERO) { a, b -> a.add(b.abs()) }
    if (gross.signum() > 0 && gross.multiply(scale, MC) > maxLeverage) {
        scale = maxLeverage.divide(gross, MC)
    }
    return weights.mapValues { (_, w) -> w.multiply(scale, MC).setScale(Money.SCALE, Money.ROUNDING) }
}
