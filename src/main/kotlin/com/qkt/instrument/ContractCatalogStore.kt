package com.qkt.instrument

import java.nio.file.Path

/**
 * Reads and writes contract catalogs under `<dataRoot>/contracts/<VENUE>/<ROOT>.json`, e.g.
 * `contracts/BINANCE_UM/BTCUSDT.json` for root `BINANCE_UM:BTCUSDT`.
 */
class ContractCatalogStore(
    dataRoot: Path,
) {
    private val files = RootFileStore(dataRoot, ".json", ContractCatalog.serializer(), { it.root }, "contract catalog")

    /** Where [root]'s catalog lives. */
    fun path(root: String): Path = files.path(root)

    /** [root]'s catalog, sorted by expiry, or null when none has been written. */
    fun read(root: String): ContractCatalog? = files.read(root)?.sorted()

    /** Writes [catalog] sorted by expiry, replacing any previous file. */
    fun write(catalog: ContractCatalog) = files.write(catalog.sorted())
}
