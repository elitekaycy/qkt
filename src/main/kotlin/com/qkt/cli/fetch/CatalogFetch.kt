package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.instrument.ContractCatalogStore
import com.qkt.marketdata.store.binance.BinanceContractCatalog
import com.qkt.marketdata.store.binance.BinanceVisionClient
import java.nio.file.Path

/** `qkt fetch VENUE:ROOT --catalog`: writes the root's futures contract catalog into the data root. */
internal object CatalogFetch {
    /** The catalog source for each venue; null when qkt has none for [venue]. */
    fun sourceFor(venue: String): BinanceContractCatalog? =
        if (venue == BinanceUmFetcher.VENUE) BinanceContractCatalog(BinanceVisionClient()) else null

    /** Builds and stores [target]'s catalog; returns a process exit code. */
    fun run(
        target: String,
        dataRoot: Path,
        catalogs: (String) -> BinanceContractCatalog? = ::sourceFor,
    ): Int {
        val source =
            catalogs(target.substringBefore(':')) ?: run {
                System.err.println(
                    "qkt: no contract catalog source for '${target.substringBefore(':')}' (supported: BINANCE_UM)",
                )
                return ExitCodes.USER_ERROR
            }
        val catalog = source.build(target)
        ContractCatalogStore(dataRoot).write(catalog)
        println(
            "qkt fetch: ${catalog.contracts.size} contracts for $target -> ${ContractCatalogStore(
                dataRoot,
            ).path(target)}",
        )
        return ExitCodes.SUCCESS
    }
}
