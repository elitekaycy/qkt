package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogStore
import com.qkt.instrument.OptionRoot
import com.qkt.marketdata.store.deribit.DeribitClient
import com.qkt.marketdata.store.deribit.DeribitOptionCatalog
import java.io.IOException
import java.nio.file.Path

/**
 * `qkt fetch DERIBIT:<ROOT> --catalog`: builds the option catalog of a root declared under `options:`
 * in the data root's `instruments.yaml` (its currency and settlement index come from there) and
 * writes it to `contracts/<VENUE>/<ROOT>.options.json`, merged with what was there so contracts that
 * fall out of the venue's history window stay resolvable.
 */
internal object OptionCatalogFetch {
    /** Whether [venue] lists options qkt can catalog. */
    fun handles(venue: String): Boolean = venue == DeribitClient.VENUE

    /** Builds and stores [target]'s option catalog with [build]; returns a process exit code. */
    fun run(
        target: String,
        dataRoot: Path,
        build: (
            OptionRoot,
            (String) -> Unit,
        ) -> OptionCatalog = { root, warn -> DeribitOptionCatalog(DeribitClient()).build(root, warn) },
    ): Int {
        val root = declaredOptionRoot(target, dataRoot) ?: return ExitCodes.USER_ERROR
        val built =
            try {
                build(root) { System.err.println("qkt: warning: $it") }
            } catch (e: IOException) {
                return failed(target, e)
            } catch (e: IllegalStateException) {
                return failed(target, e)
            } catch (e: IllegalArgumentException) {
                return failed(target, e)
            }
        val store = OptionCatalogStore(dataRoot)
        val catalog = merged(store.read(target), built)
        store.write(catalog)
        val counts = "${catalog.contracts.size} options and ${catalog.deliveryPrices.size} delivery prices"
        println("qkt fetch: $counts for $target -> ${store.path(target)}")
        return ExitCodes.SUCCESS
    }

    /**
     * [built] plus every contract of [existing] the venue no longer lists (its history window rolls,
     * and older backtests still resolve them); fresh records and delivery prices win on overlap.
     */
    private fun merged(
        existing: OptionCatalog?,
        built: OptionCatalog,
    ): OptionCatalog {
        if (existing == null) return built
        val fresh = built.contracts.map { it.symbol }.toSet()
        val contracts =
            (built.contracts + existing.contracts.filter { it.symbol !in fresh })
                .sortedWith(compareBy({ it.expiryMs }, { it.strike.toBigDecimal() }, { it.right }))
        return OptionCatalog(built.root, contracts, existing.deliveryPrices + built.deliveryPrices)
    }

    private fun failed(
        target: String,
        cause: Exception,
    ): Int {
        System.err.println("qkt: could not build the option catalog for $target: ${cause.message}")
        return ExitCodes.USER_ERROR
    }
}
