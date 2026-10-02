package com.qkt.cli.fetch

import com.qkt.cli.Config
import com.qkt.cli.ExitCodes
import com.qkt.cli.openAccounts
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogSource
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.FuturesRootsFile
import com.qkt.instrument.OptionRootsFile
import com.qkt.marketdata.store.binance.BinanceContractCatalog
import com.qkt.marketdata.store.binance.BinanceVisionClient
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * `qkt fetch VENUE:ROOT --catalog`: writes the root's contract catalogs into the data root. A Deribit root
 * declared under `options:` gets its option catalog; a root declared under `futures:` (or any Binance
 * USDⓈ-M root) gets its futures catalog, from Binance's archive or from the venue's own listing through the
 * `brokers:` account named after the venue (a `type: gateway` account). A root may be both.
 */
internal object CatalogFetch {
    /** Builds every catalog [target] is declared for; returns a process exit code. */
    fun forTarget(
        target: String,
        dataRoot: Path,
        configOption: String?,
    ): Int {
        val venue = target.substringBefore(':')
        val instruments = dataRoot.resolve("instruments.yaml")
        val futures =
            venue == BinanceUmFetcher.VENUE ||
                (Files.exists(instruments) && FuturesRootsFile.load(instruments).any { it.root == target })
        val options = OptionCatalogFetch.handles(venue) && (!futures || declaredOptions(target, instruments))
        val optionCode = if (options) OptionCatalogFetch.run(target, dataRoot) else ExitCodes.SUCCESS
        val futuresCode =
            if (futures || !options) run(target, dataRoot) { sourceFor(it, configOption) } else ExitCodes.SUCCESS
        return if (optionCode != ExitCodes.SUCCESS) optionCode else futuresCode
    }

    /** Binance's archive for its venue, else the venue listing of the `brokers:` account named [venue]. */
    fun sourceFor(
        venue: String,
        configOption: String?,
    ): ContractCatalogSource? {
        if (venue == BinanceUmFetcher.VENUE) return BinanceContractCatalog(BinanceVisionClient())
        val config = (configOption?.let { Path.of(it) } ?: Config.locate())?.let(Config::load) ?: return null
        return try {
            config.openAccounts().byName(venue)?.contractCatalogs
        } catch (e: IllegalArgumentException) {
            null.also { System.err.println("qkt: failed to open broker '$venue': ${e.message}") }
        } catch (e: IllegalStateException) {
            null.also { System.err.println("qkt: failed to open broker '$venue': ${e.message}") }
        }
    }

    /** Builds [target]'s futures catalog from [catalogs]' source and stores it; returns a process exit code. */
    fun run(
        target: String,
        dataRoot: Path,
        catalogs: (String) -> ContractCatalogSource?,
    ): Int {
        val source =
            catalogs(target.substringBefore(':')) ?: run {
                System.err.println(
                    "qkt: no contract catalog source for '${target.substringBefore(':')}' " +
                        "(BINANCE_UM, or a type: gateway account of that name in --config)",
                )
                return ExitCodes.USER_ERROR
            }
        val catalog =
            try {
                source.build(target) { System.err.println("qkt: warning: $it") }
            } catch (e: IOException) {
                return failed(target, e)
            } catch (e: RuntimeException) {
                return failed(target, e)
            }
        val store = ContractCatalogStore(dataRoot)
        val merged = merged(store.read(target), catalog)
        store.write(merged)
        println("qkt fetch: ${merged.contracts.size} contracts for $target -> ${store.path(target)}")
        return ExitCodes.SUCCESS
    }

    /**
     * [built] plus every stored contract the venue no longer lists (a gateway lists a contract for 30 days
     * after it expires). A settled delivery price never changes, so one stored earlier outlives an outage now.
     */
    private fun merged(
        stored: ContractCatalog?,
        built: ContractCatalog,
    ): ContractCatalog {
        val old = stored?.contracts.orEmpty().associateBy { it.symbol }
        val fresh = built.contracts.map { it.copy(deliveryPrice = it.deliveryPrice ?: old[it.symbol]?.deliveryPrice) }
        val listed = fresh.map { it.symbol }.toSet()
        return built.copy(contracts = fresh + old.values.filter { it.symbol !in listed })
    }

    private fun declaredOptions(
        target: String,
        instruments: Path,
    ): Boolean = Files.exists(instruments) && OptionRootsFile.load(instruments).any { it.root == target }

    private fun failed(
        target: String,
        cause: Exception,
    ): Int {
        System.err.println("qkt: could not build the contract catalog for $target: ${cause.message}")
        return ExitCodes.USER_ERROR
    }
}
