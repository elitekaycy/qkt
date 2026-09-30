package com.qkt.cli

import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.FuturesRootsFile
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.LayeredInstrumentRegistry
import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.instrument.YamlInstrumentRegistry
import java.nio.file.Files
import java.nio.file.Path

/**
 * The instrument metadata a backtest resolves symbols against: the `instruments:` entries of the
 * instruments file, then its `futures:` roots joined with `<dataRoot>/contracts/` catalogs, then the
 * built-in standard table. A run without an instruments file uses the standard table alone.
 */
internal object BacktestInstruments {
    /** The registry for [dataRoot], reading [explicit] (`--instruments`) or `<dataRoot>/instruments.yaml`. */
    fun registry(
        dataRoot: Path,
        explicit: Path?,
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
                    ContractCatalogRegistry.load(roots, ContractCatalogStore(dataRoot)),
                )
            }
        return LayeredInstrumentRegistry(
            listOf(YamlInstrumentRegistry.load(path)) + futures + StandardInstrumentRegistry,
        )
    }
}
