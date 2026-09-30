package com.qkt.instrument

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * Reads and writes contract catalogs under `<dataRoot>/contracts/<VENUE>/<ROOT>.json`, e.g.
 * `contracts/BINANCE_UM/BTCUSDT.json` for root `BINANCE_UM:BTCUSDT`.
 */
class ContractCatalogStore(
    private val dataRoot: Path,
) {
    private val json = Json { prettyPrint = true }

    /** Where [root]'s catalog lives. */
    fun path(root: String): Path {
        QktSymbols.requireFileSafe(root)
        return dataRoot
            .resolve(
                "contracts",
            ).resolve(root.substringBefore(':'))
            .resolve("${root.substringAfter(':')}.json")
    }

    /** [root]'s catalog, sorted by expiry, or null when none has been written. */
    fun read(root: String): ContractCatalog? {
        val file = path(root)
        if (!Files.exists(file)) return null
        val catalog =
            try {
                json.decodeFromString(ContractCatalog.serializer(), Files.readString(file))
            } catch (e: IllegalArgumentException) {
                // kotlinx's SerializationException is an IllegalArgumentException, as are the
                // catalog's own validation failures; both mean the file is malformed.
                throw IllegalStateException("$file: invalid contract catalog: ${e.message}", e)
            }
        require(catalog.root == root) { "$file holds catalog for '${catalog.root}', expected '$root'" }
        return catalog.sorted()
    }

    /** Writes [catalog] sorted by expiry, replacing any previous file. */
    fun write(catalog: ContractCatalog) {
        val file = path(catalog.root)
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(ContractCatalog.serializer(), catalog.sorted()) + "\n")
    }
}
