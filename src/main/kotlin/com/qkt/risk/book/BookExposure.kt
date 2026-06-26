package com.qkt.risk.book

import com.qkt.accounting.AccountingEngine
import com.qkt.common.Money
import java.math.BigDecimal

/** One open position leg attributed to a strategy, for book-exposure aggregation. */
data class Leg(
    val strategyId: String,
    val symbol: String,
    val signedQty: BigDecimal,
    val price: BigDecimal,
    val contractSize: BigDecimal,
)

/**
 * Book exposure in account currency.
 * - [gross]: total footprint, every leg counted (no netting) — what the desk actually has on.
 * - [net]: directional exposure after netting opposing legs within each symbol, summed across symbols.
 * - [perSymbolNet]: signed net notional per symbol (for concentration checks).
 */
data class Exposure(
    val gross: BigDecimal,
    val net: BigDecimal,
    val perSymbolNet: Map<String, BigDecimal>,
)

/**
 * Aggregate [legs] into book exposure. Native notional = signedQty x price x contractSize, then
 * converted to the account currency by [accounting].
 * e.g. strat A long 1 X @100 and strat B short 1 X @100 → gross 200, net 0 (a hedged book).
 */
fun bookExposure(
    legs: List<Leg>,
    accounting: AccountingEngine = AccountingEngine(),
    timestampMs: Long = 0L,
): Exposure {
    var gross = Money.ZERO
    val perSymbol = HashMap<String, BigDecimal>()
    for (l in legs) {
        val nativeNotional = l.signedQty.multiply(l.price, Money.CONTEXT).multiply(l.contractSize, Money.CONTEXT)
        val notional =
            accounting
                .convertNotional(
                    symbol = l.symbol,
                    nativeNotional = nativeNotional,
                    timestamp = timestampMs,
                    referencePrice = l.price,
                ).account.amount
        gross = gross.add(notional.abs())
        perSymbol[l.symbol] = (perSymbol[l.symbol] ?: Money.ZERO).add(notional)
    }
    val net = perSymbol.values.fold(Money.ZERO) { acc, v -> acc.add(v.abs()) }
    return Exposure(
        gross = gross.setScale(Money.SCALE, Money.ROUNDING),
        net = net.setScale(Money.SCALE, Money.ROUNDING),
        perSymbolNet = perSymbol.mapValues { it.value.setScale(Money.SCALE, Money.ROUNDING) },
    )
}
