package com.qkt.marketdata.store.deribit

import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRight
import com.qkt.instrument.OptionRoot
import java.math.BigDecimal

/**
 * Builds an option root's catalog from Deribit: every live and expired option whose name belongs to
 * the root (`BTC_USDC-…` for `DERIBIT:BTC_USDC`), kept only when it is linear (or, on the history
 * host, untyped) and priced and settled in the root's currency, and only when its name agrees with the venue's own strike, right and
 * expiry — each skipped contract is reported through `warn`. The root's settlement index delivery
 * prices are paged in from `get_delivery_prices`. Contracts are sorted by expiry, strike, then right.
 */
class DeribitOptionCatalog(
    private val client: DeribitClient,
) {
    /** The catalog of [root]. */
    fun build(
        root: OptionRoot,
        warn: (String) -> Unit,
    ): OptionCatalog {
        val query = mapOf("currency" to root.currency, "kind" to "option")
        val prefix = "${root.root.substringAfter(':')}-"
        val listings =
            (client.instruments(expired = false, query) + client.instruments(expired = true, query))
                .filter { it.name.startsWith(prefix) }
                .mapNotNull { listing(it, root, warn) }
                .distinctBy { it.symbol }
                .sortedWith(compareBy({ it.expiryMs }, { BigDecimal(it.strike) }, { it.right }))
        return OptionCatalog(root.root, listings, deliveryPrices(root.underlyingIndex))
    }

    private fun listing(
        instrument: DeribitInstrument,
        root: OptionRoot,
        warn: (String) -> Unit,
    ): OptionListing? {
        val name = instrument.name
        val (type, settles, premium) =
            Triple(
                instrument.type,
                instrument.settlementCurrency,
                instrument.counterCurrency,
            )
        if ((type != null && type != "linear") || settles != root.currency || premium != root.currency) {
            warn(
                "skipped $name: ${type ?: "type unknown"}, settled in $settles, priced in $premium; ${root.root} trades linear ${root.currency}",
            )
            return null
        }
        val parsed = DeribitOptionNames.parse(name)
        val strike = instrument.strike?.content?.toBigDecimalOrNull()
        val right = if (instrument.optionType == "call") OptionRight.CALL else OptionRight.PUT
        if (parsed == null ||
            strike == null ||
            parsed.strike.compareTo(strike) != 0 ||
            parsed.right != right ||
            parsed.expiryMs != instrument.expiryMs
        ) {
            warn(
                "skipped $name: the venue lists strike ${strike?.toPlainString()}, $right, expiry ${instrument.expiryMs}, which its name does not match",
            )
            return null
        }
        return OptionListing(name, strike.stripTrailingZeros().toPlainString(), right.name.lowercase(), parsed.expiryMs)
    }

    private fun deliveryPrices(index: String): Map<String, String> {
        val prices = LinkedHashMap<String, String>()
        var offset = 0
        do {
            val page = client.deliveryPrices(index, offset)
            for (row in page.data) prices[row.date] = BigDecimal(row.price.content).toPlainString()
            offset += page.data.size
        } while (page.data.isNotEmpty() && offset < page.total)
        return prices
    }
}
