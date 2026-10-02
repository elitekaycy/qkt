package com.qkt.instrument

import com.qkt.common.Clock
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * The option catalogs of [roots] for a live session, which runs for days while venues list new
 * expiries: it answers from an [OptionCatalogRegistry] and, at most every [recheckMs] by [clock],
 * reloads it when a catalog file under [dataRoot] changed (a scheduled `qkt fetch <root> --catalog`
 * writes them atomically). A catalog that fails to load keeps the last good registry, logged. Every
 * caller asks [options] or [lookup] per use, so a reload reaches them all; a lookup costs one clock read
 * between checks.
 */
class RefreshingOptionCatalogs(
    private val roots: List<OptionRoot>,
    private val dataRoot: java.nio.file.Path,
    private val clock: Clock,
    private val recheckMs: Long = 60_000,
) : InstrumentRegistry {
    private val log = LoggerFactory.getLogger(RefreshingOptionCatalogs::class.java)
    private val store = OptionCatalogStore(dataRoot)
    private val checkedAt = AtomicLong(clock.now())

    @Volatile private var stamps = stamps()

    @Volatile private var current = OptionCatalogRegistry.load(roots, dataRoot)

    override fun lookup(qktSymbol: String): InstrumentMeta? = registry().lookup(qktSymbol)

    override fun missingReason(qktSymbol: String): String? = registry().missingReason(qktSymbol)

    override fun options(): OptionDirectory = registry()

    private fun registry(): OptionCatalogRegistry {
        val now = clock.now()
        val last = checkedAt.get()
        if (now - last >= recheckMs && checkedAt.compareAndSet(last, now)) reloadIfChanged()
        return current
    }

    private fun reloadIfChanged() {
        val seen = stamps()
        if (seen == stamps) return
        try {
            current = OptionCatalogRegistry.load(roots, dataRoot)
            stamps = seen
            log.info("option catalogs reloaded: {}", roots.joinToString { it.root })
        } catch (e: RuntimeException) {
            log.error("option catalogs not reloaded, keeping the last good ones: {}", e.message)
        }
    }

    /** Each catalog file's identity, size and modification time; absent files have none. */
    private fun stamps(): List<List<Any?>> =
        roots.map { root ->
            try {
                val attributes = Files.readAttributes(store.path(root.root), BasicFileAttributes::class.java)
                listOf(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime())
            } catch (e: NoSuchFileException) {
                emptyList()
            }
        }
}
