package com.qkt.marketdata.store.binance

import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ListedContract

/**
 * Builds the contract catalog of a Binance USDⓈ-M root from the public file listing (every
 * quarterly that ever had monthly klines) and the delivery-price endpoint (settled contracts).
 */
class BinanceContractCatalog(
    private val client: BinanceVisionClient,
) {
    /** The catalog of [root], e.g. `BINANCE_UM:BTCUSDT`. */
    fun build(root: String): ContractCatalog {
        require(root.startsWith("$VENUE:")) { "Binance catalogs are for $VENUE roots, got '$root'" }
        val pair = root.substringAfter(':')
        val codes =
            client
                .listPrefixes("data/futures/um/monthly/klines/${pair}_")
                .map { it.trimEnd('/').substringAfterLast('/') }
        // The endpoint stamps a delivery at 00:00 UTC of its date; the contract settles at 08:00 that day.
        val delivered = client.deliveryPrices(pair).mapKeys { (ms, _) -> Math.floorDiv(ms, MS_PER_DAY) }
        val contracts =
            codes.mapNotNull { code ->
                BinanceQuarterly
                    .expiryMs(
                        code,
                    )?.let { ListedContract(code, it, delivered[Math.floorDiv(it, MS_PER_DAY)]) }
            }
        return ContractCatalog(root, contracts).sorted()
    }

    private companion object {
        const val VENUE = "BINANCE_UM"
        const val MS_PER_DAY = 86_400_000L
    }
}
