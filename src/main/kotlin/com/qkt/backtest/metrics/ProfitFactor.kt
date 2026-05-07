package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal

fun profitFactor(realizeds: List<BigDecimal>): BigDecimal? {
    if (realizeds.isEmpty()) return null
    val wins = realizeds.filter { it.signum() > 0 }.fold(Money.ZERO) { a, v -> a.add(v) }
    val losses = realizeds.filter { it.signum() < 0 }.fold(Money.ZERO) { a, v -> a.add(v) }
    if (losses.signum() == 0) {
        if (wins.signum() == 0) return null
        return null
    }
    return wins.divide(losses.abs(), Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
}
