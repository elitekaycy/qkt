package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal

fun calmar(
    totalReturn: BigDecimal,
    maxDrawdown: BigDecimal,
): BigDecimal? {
    if (maxDrawdown.signum() == 0) return null
    return totalReturn.divide(maxDrawdown, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
}
