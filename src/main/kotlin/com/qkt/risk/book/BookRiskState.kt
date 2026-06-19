package com.qkt.risk.book

import com.qkt.common.Money
import java.math.BigDecimal

/**
 * Latest book-risk decision snapshot, read by the pre-trade gate. [limitBreach] answers whether a
 * risk-increasing order adding [signedNotional] on [symbol] would push the book past a configured
 * cap — returns a human-readable reason, or null when allowed. Caps are multiples of [capital];
 * non-positive capital disables enforcement (no basis to take a fraction of).
 */
data class BookRiskState(
    val capital: BigDecimal,
    val grossExposure: BigDecimal,
    val perSymbolNet: Map<String, BigDecimal>,
    val limits: BookLimits?,
    val deRiskFactor: BigDecimal = BigDecimal.ONE,
) {
    fun limitBreach(
        symbol: String,
        signedNotional: BigDecimal,
    ): String? {
        val lim = limits ?: return null
        if (capital.signum() <= 0) return null

        lim.maxGrossExposure?.let { mg ->
            val newGross = grossExposure.add(signedNotional.abs())
            if (newGross > mg.multiply(capital, Money.CONTEXT)) {
                return "book gross exposure ${newGross.toPlainString()} > ${mg.toPlainString()}x capital"
            }
        }

        val newSymbolNet = (perSymbolNet[symbol] ?: Money.ZERO).add(signedNotional)
        lim.maxSymbolConcentration?.let { mc ->
            if (newSymbolNet.abs() > mc.multiply(capital, Money.CONTEXT)) {
                val v = newSymbolNet.abs().toPlainString()
                return "book $symbol concentration $v > ${mc.toPlainString()}x cap"
            }
        }

        lim.maxNetExposure?.let { mn ->
            var net = Money.ZERO
            var sawSymbol = false
            for ((s, v) in perSymbolNet) {
                if (s == symbol) {
                    net = net.add(newSymbolNet.abs())
                    sawSymbol = true
                } else {
                    net = net.add(v.abs())
                }
            }
            if (!sawSymbol) net = net.add(newSymbolNet.abs())
            if (net > mn.multiply(capital, Money.CONTEXT)) {
                return "book net exposure ${net.toPlainString()} > ${mn.toPlainString()}x capital"
            }
        }
        return null
    }
}
