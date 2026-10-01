package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogStore
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.OptionRootsFile
import com.qkt.marketdata.store.deribit.DeribitClient
import com.qkt.marketdata.store.deribit.DeribitOptionCatalog
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * `qkt fetch DERIBIT:<ROOT> --catalog`: builds the option catalog of a root declared under `options:`
 * in the data root's `instruments.yaml` (its currency and settlement index come from there) and
 * writes it to `contracts/<VENUE>/<ROOT>.options.json`.
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
        val instruments = dataRoot.resolve("instruments.yaml")
        val root =
            (if (Files.exists(instruments)) OptionRootsFile.load(instruments) else emptyList()).firstOrNull {
                it.root ==
                    target
            }
                ?: run {
                    System.err.println("qkt: $target is not declared under options: in $instruments")
                    return ExitCodes.USER_ERROR
                }
        val catalog =
            try {
                build(root) { System.err.println("qkt: warning: $it") }
            } catch (e: IOException) {
                System.err.println("qkt: could not build the option catalog for $target: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        val store = OptionCatalogStore(dataRoot)
        store.write(catalog)
        val counts = "${catalog.contracts.size} options and ${catalog.deliveryPrices.size} delivery prices"
        println("qkt fetch: $counts for $target -> ${store.path(target)}")
        return ExitCodes.SUCCESS
    }
}
