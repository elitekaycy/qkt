package com.qkt.cli

import com.qkt.common.Clock
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.EventCatalogRegistry
import com.qkt.instrument.EventFamiliesFile
import com.qkt.instrument.FundingRateStore
import com.qkt.instrument.FuturesRootsFile
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionRootsFile
import com.qkt.instrument.RefreshingOptionCatalogs
import com.qkt.instrument.RollHistoryStore
import java.nio.file.Path

/**
 * The registry layers an instruments file's derivative sections declare, in lookup order: its `futures:` roots joined
 * with their contract catalogs (and each perpetual's stored funding rates, unless [funding] is off), its `options:`
 * roots joined with their option catalogs (reloading on change when a live session passes [liveClock]), and its
 * `events:` families joined with their event catalogs. A section the file lacks adds no layer.
 */
internal object DerivativeLayers {
    fun of(
        path: Path,
        dataRoot: Path,
        liveClock: Clock?,
        funding: Boolean,
    ): List<InstrumentRegistry> {
        val roots = FuturesRootsFile.load(path)
        val optionRoots = OptionRootsFile.load(path)
        val families = EventFamiliesFile.load(path)
        return listOfNotNull<InstrumentRegistry>(
            roots.takeIf { it.isNotEmpty() }?.let {
                ContractCatalogRegistry.load(
                    it,
                    ContractCatalogStore(dataRoot),
                    RollHistoryStore(dataRoot),
                    FundingRateStore(dataRoot).takeIf { funding },
                )
            },
            optionRoots.takeIf { it.isNotEmpty() }?.let {
                liveClock?.let { clock -> RefreshingOptionCatalogs(it, dataRoot, clock) }
                    ?: OptionCatalogRegistry.load(it, dataRoot)
            },
            families.takeIf { it.isNotEmpty() }?.let { EventCatalogRegistry.load(it, dataRoot) },
        )
    }
}
