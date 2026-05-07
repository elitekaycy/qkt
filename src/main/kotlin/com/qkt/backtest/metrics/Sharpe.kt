package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal
import java.math.MathContext

private val EPSILON = BigDecimal("0.00000001")

fun sharpe(
    equityCurve: List<BigDecimal>,
    annualizationFactor: BigDecimal,
): BigDecimal? {
    if (equityCurve.size < 2) return null

    val returns = mutableListOf<BigDecimal>()
    for (i in 0 until equityCurve.size - 1) {
        val prev = equityCurve[i]
        val next = equityCurve[i + 1]
        val denom = prev.abs().max(EPSILON)
        val r = next.subtract(prev).divide(denom, Money.CONTEXT)
        returns.add(r)
    }

    val n = BigDecimal(returns.size)
    val mean = returns.fold(Money.ZERO) { a, v -> a.add(v) }.divide(n, Money.CONTEXT)
    val variance =
        returns
            .map { it.subtract(mean).pow(2) }
            .fold(Money.ZERO) { a, v -> a.add(v) }
            .divide(BigDecimal(returns.size - 1).max(BigDecimal.ONE), Money.CONTEXT)
    if (variance.signum() <= 0) return null

    val stddev = variance.sqrt(MathContext.DECIMAL64)
    if (stddev.signum() == 0) return null

    val annFactor = annualizationFactor.sqrt(MathContext.DECIMAL64)
    return mean
        .divide(stddev, Money.CONTEXT)
        .multiply(annFactor, Money.CONTEXT)
        .setScale(Money.SCALE, Money.ROUNDING)
}
