package com.qkt.cli

import com.qkt.instrument.EventCatalogRegistry
import com.qkt.instrument.EventFamiliesFile
import com.qkt.instrument.InstrumentMeta
import java.nio.file.Files
import java.nio.file.Path

/**
 * The event contracts a backtest's moving symbol sets may pick from (runtime membership plan §4.4): every contract of
 * the instruments file's `events:` families, read from its event catalog under `<dataRoot>/contracts/` with its
 * [com.qkt.instrument.EventTerms], so a set sees a contract only from its `openAtMs` until its expiry.
 */
internal object EventCatalogRows {
    /** The catalogued contracts of the families [args]' instruments file declares; none without a file or family. */
    fun of(
        args: Args,
        dataRoot: Path,
    ): List<InstrumentMeta> {
        val path = args.option("instruments")?.let(Path::of) ?: dataRoot.resolve("instruments.yaml")
        if (!Files.exists(path)) return emptyList()
        val families = EventFamiliesFile.load(path)
        val registry = EventCatalogRegistry.load(families, dataRoot)
        return families.flatMap { family -> registry.contracts(family.root).mapNotNull(registry::lookup) }
    }
}
