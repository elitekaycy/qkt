package com.qkt.cli

import com.qkt.common.Clock
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.FuturesRootsFile
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.LayeredInstrumentRegistry
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionRootsFile
import com.qkt.instrument.RefreshingOptionCatalogs
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.instrument.YamlInstrumentRegistry
import java.nio.file.Files
import java.nio.file.Path

/**
 * The instrument metadata backtests and live sessions resolve symbols against: the `instruments:`
 * entries of the instruments file, then its `futures:` roots joined with `<dataRoot>/contracts/`
 * catalogs, then its `options:` roots joined with their option catalogs, then the built-in standard
 * table. Without an instruments file it is the standard table alone.
 */
internal object InstrumentFiles {
    /**
     * The registry for [dataRoot], reading [explicit] (`--instruments`) or `<dataRoot>/instruments.yaml`.
     * Fails before any data is read when one of [symbols] belongs to a declared futures root but
     * cannot be resolved (e.g. a contract missing from its catalog). A live session passes [liveClock]:
     * its option catalogs then reload when their files change ([RefreshingOptionCatalogs]), as venues list
     * new expiries while it runs.
     */
    fun registry(
        dataRoot: Path,
        explicit: Path?,
        symbols: Collection<String> = emptyList(),
        liveClock: Clock? = null,
    ): InstrumentRegistry {
        val path = explicit ?: dataRoot.resolve("instruments.yaml")
        if (!Files.exists(path)) {
            if (explicit != null) throw BacktestContext.Companion.SetupError("--instruments file not found: $path")
            return StandardInstrumentRegistry
        }
        val roots = FuturesRootsFile.load(path)
        val futures =
            if (roots.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    ContractCatalogRegistry.load(roots, ContractCatalogStore(dataRoot), RollHistoryStore(dataRoot)),
                )
            }
        val optionRoots = OptionRootsFile.load(path)
        val options =
            if (optionRoots.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    liveClock?.let { RefreshingOptionCatalogs(optionRoots, dataRoot, it) }
                        ?: OptionCatalogRegistry.load(optionRoots, dataRoot),
                )
            }
        val registry =
            LayeredInstrumentRegistry(
                listOf(YamlInstrumentRegistry.load(path)) + futures + options + StandardInstrumentRegistry,
            )
        val unresolved =
            symbols.firstNotNullOfOrNull { s ->
                if (registry.lookup(s) ==
                    null
                ) {
                    registry.missingReason(s)
                } else {
                    null
                }
            }
        if (unresolved != null) throw BacktestContext.Companion.SetupError(unresolved)
        return registry
    }
}
