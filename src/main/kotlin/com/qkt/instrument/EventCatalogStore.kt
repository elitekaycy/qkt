package com.qkt.instrument

import java.nio.file.Path

/**
 * Reads and writes event catalogs under `<dataRoot>/contracts/<VENUE>/<FAMILY>.events.json`, e.g.
 * `contracts/KALSHI/KXINXU.events.json` for the family `KALSHI:KXINXU`.
 */
class EventCatalogStore(
    dataRoot: Path,
) {
    private val files = RootFileStore(dataRoot, ".events.json", EventCatalog.serializer(), { it.root }, "event catalog")

    /** Where [root]'s catalog lives. */
    fun path(root: String): Path = files.path(root)

    /** [root]'s catalog in expiry order, or null when none has been written. */
    fun read(root: String): EventCatalog? = files.read(root)?.sorted()

    /** Writes [catalog] in expiry order, replacing any previous file. */
    fun write(catalog: EventCatalog) = files.write(catalog.sorted())
}
