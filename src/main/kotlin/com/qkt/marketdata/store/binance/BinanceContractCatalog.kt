package com.qkt.marketdata.store.binance

import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ListedContract
import java.io.IOException

/**
 * Builds the contract catalog of a Binance USDⓈ-M root from the public file listing (every
 * quarterly that ever had monthly klines) and the delivery-price endpoint (settled contracts).
 */
class BinanceContractCatalog(
    private val client: BinanceVisionClient,
) {
    /**
     * The catalog of [root], e.g. `BINANCE_UM:BTCUSDT`. Contracts come from both the monthly and the
     * daily file listings (a newly listed quarterly has daily files before its first monthly file).
     * Delivery prices are optional: when the endpoint cannot be read the catalog is built without
     * them and the reason goes to [warn].
     */
    fun build(
        root: String,
        warn: (String) -> Unit = {},
    ): ContractCatalog {
        require(root.startsWith("$VENUE:")) { "Binance catalogs are for $VENUE roots, got '$root'" }
        val pair = root.substringAfter(':')
        val codes =
            (listCodes("monthly", pair) + listCodes("daily", pair)).distinct()
        // The endpoint stamps a delivery at 00:00 UTC of its date; the contract settles at 08:00 that day.
        val delivered =
            try {
                client.deliveryPrices(pair).mapKeys { (ms, _) -> Math.floorDiv(ms, MS_PER_DAY) }
            } catch (e: IOException) {
                warnUnpriced(warn, pair, e)
            } catch (e: IllegalStateException) {
                warnUnpriced(warn, pair, e)
            } catch (e: IllegalArgumentException) {
                warnUnpriced(warn, pair, e)
            }
        val contracts =
            codes.mapNotNull { code ->
                BinanceQuarterly
                    .expiryMs(
                        code,
                    )?.let { ListedContract(code, it, delivered[Math.floorDiv(it, MS_PER_DAY)]) }
            }
        return ContractCatalog(root, contracts).sorted()
    }

    private fun warnUnpriced(
        warn: (String) -> Unit,
        pair: String,
        cause: Exception,
    ): Map<Long, String> {
        warn("delivery prices for $pair unavailable (${cause.message}); catalog written without them")
        return emptyMap()
    }

    private fun listCodes(
        period: String,
        pair: String,
    ): List<String> =
        client.listPrefixes("data/futures/um/$period/klines/${pair}_").map {
            it.trimEnd('/').substringAfterLast('/')
        }

    private companion object {
        const val VENUE = "BINANCE_UM"
        const val MS_PER_DAY = 86_400_000L
    }
}
