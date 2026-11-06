package com.qkt.connector.gateway

import com.qkt.accounting.CostKind
import com.qkt.accounting.CurrencyPair
import com.qkt.accounting.VenueCost
import com.qkt.instrument.QuoteCurrencyGuard
import java.math.BigDecimal

/**
 * A venue deal's scalar costs (`commission`, `swap`, `fee`), which a `broker.deal` states in the account currency
 * (#1662). A cost the venue takes in the coin a spot pair buys (`0.0000125 ETH` on `ETHUSD`) is priced at the fill,
 * the rule qkt's accounting books it by ([CurrencyPair]: the pair's base at the fill price is its quote). Amounts
 * are added only in one currency: the pair's quote, or for a symbol with no pair the first cost's own. A cost in
 * any other currency stays out of the scalar; the deal's typed costs keep every cost as paid, with its currency.
 */
internal object GatewayDealCosts {
    /** What [costs] of [kinds] paid on a fill of [symbol] at [price], negative (MT5's sign); null when none was. */
    fun paid(
        costs: List<VenueCost>,
        symbol: String,
        price: BigDecimal,
        vararg kinds: CostKind,
    ): BigDecimal? {
        val picked = costs.filter { it.kind in kinds }.takeIf { it.isNotEmpty() } ?: return null
        val pair = CurrencyPair.of(symbol)
        val target = pair?.quote ?: picked.first().amount.normalizedCurrency
        return picked.fold(BigDecimal.ZERO) { sum, cost ->
            val currency = cost.amount.normalizedCurrency
            val amount = cost.amount.amount
            when {
                QuoteCurrencyGuard.sameCurrency(currency, target) -> sum - amount
                pair != null && currency == pair.base -> sum - amount.multiply(price)
                else -> sum
            }
        }
    }
}
