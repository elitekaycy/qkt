package com.qkt.marketdata.store

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.marketdata.ConcatenatedTickFeed
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.RangeClippedTickFeed
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.openDayFeed
import com.qkt.marketdata.source.MarketRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/**
 * File-backed tick [DataStore] rooted at [root]: resolves request ranges against per-symbol
 * manifests, fetches missing days through [fetcher] and commits them to the manifest under a
 * lock, and opens merged, range-clipped feeds over the stored day files.
 */
class DefaultDataStore(
    override val root: Path,
    private val fetcher: DataFetcher? = null,
    private val clock: Clock = SystemClock(),
    private val manifestStore: ManifestStore = ManifestStore(root, clock),
) : DataStore {
    private val log = LoggerFactory.getLogger(DefaultDataStore::class.java)
    private val lock = ManifestLock(root)

    override fun manifest(symbol: String): Manifest = manifestStore.read(symbol)

    override fun dayFile(
        symbol: String,
        day: LocalDate,
    ): Path? = TickDayFiles.find(root, symbol, day)

    override fun openFeed(request: MarketRequest): TickFeed {
        val (fromMs, toMs) = resolveRangeMs(request)
        materializeMissing(request.symbols, fromMs, toMs)

        val perSymbol =
            request.symbols.map { sym ->
                val days = daysCovering(fromMs, toMs)
                val factories: List<() -> TickFeed> =
                    days.mapNotNull { dayFile(sym, it) }.map { path -> { openDayFeed(path) } }
                ConcatenatedTickFeed(factories)
            }
        val merged: TickFeed = if (perSymbol.size == 1) perSymbol[0] else MergingTickFeed(perSymbol)
        return RangeClippedTickFeed(merged, fromMs = fromMs, toMs = toMs)
    }

    override fun resolveRange(request: MarketRequest): Pair<Instant, Instant> {
        val (fromMs, toMs) = resolveRangeMs(request)
        return Instant.ofEpochMilli(fromMs) to Instant.ofEpochMilli(toMs)
    }

    override fun prefetch(request: MarketRequest) {
        val (fromMs, toMs) = resolveRangeMs(request)
        materializeMissing(request.symbols, fromMs, toMs)
    }

    override fun rebuildManifests() =
        lock.withLock {
            val symbolsDir = root.resolve("symbols")
            if (!Files.exists(symbolsDir)) return@withLock
            Files.list(symbolsDir).use { stream ->
                for (symDir in stream) {
                    if (!Files.isDirectory(symDir)) continue
                    val sym = symDir.fileName.toString()
                    val days = TickDayFiles.storedDays(symDir)
                    if (days.isEmpty()) {
                        // No day files left (e.g. every day was deleted for a refetch). Clear the
                        // manifest so a later prefetch re-materializes it; leaving the old ranges would
                        // make the store claim coverage it no longer has and skip the refetch.
                        manifestStore.write(Manifest(symbol = sym, ranges = emptyList()))
                        continue
                    }
                    manifestStore.write(Manifest(symbol = sym, ranges = contiguousDayRanges(days)))
                }
            }
        }

    /**
     * Delete a cached day-file and reconcile the manifest to what is left on disk, as one atomic
     * step against other writers. Used to repair a partial day: drop it, then a later prefetch
     * re-materializes it. e.g. `dropDay("XAUUSD", 2026-06-10)` removes that day and rebuilds the
     * manifest without it.
     */
    fun dropDay(
        symbol: String,
        day: LocalDate,
    ) = lock.withLock {
        dayFile(symbol, day)?.let { Files.deleteIfExists(it) }
        if (manifestStore.read(symbol).ranges.isNotEmpty()) rebuildManifests()
    }

    private fun resolveRangeMs(request: MarketRequest): Pair<Long, Long> {
        val (from, to) =
            if (request.from != null && request.to != null) {
                request.from to request.to
            } else {
                val ranges = request.symbols.map { manifestStore.read(it).ranges }
                check(ranges.all { it.isNotEmpty() }) {
                    "no cached data for symbols ${request.symbols}; specify MarketRequest(symbols, from, to) to trigger a fetch"
                }
                val earliest = ranges.maxOf { LocalDate.parse(it.first().from) }
                val latest = ranges.minOf { LocalDate.parse(it.last().to) }
                check(earliest < latest) {
                    "requested symbols ${request.symbols} have no overlapping cached date range"
                }
                val resolvedFrom = request.from ?: earliest.atStartOfDay(ZoneOffset.UTC).toInstant()
                val resolvedTo = request.to ?: latest.atStartOfDay(ZoneOffset.UTC).toInstant()
                check(resolvedFrom < resolvedTo) {
                    "resolved range is empty for symbols ${request.symbols}: from=$resolvedFrom to=$resolvedTo " +
                        "(request.from=${request.from}, request.to=${request.to}, cached earliest=$earliest, cached latest=$latest)"
                }
                resolvedFrom to resolvedTo
            }
        return from.toEpochMilli() to to.toEpochMilli()
    }

    private fun materializeMissing(
        symbols: List<String>,
        fromMs: Long,
        toMs: Long,
    ) {
        val days = daysCovering(fromMs, toMs)
        for (sym in symbols) {
            val manifest = manifestStore.read(sym)
            val covered = manifest.ranges.flatMap { dayList(it) }.toSet()
            val missing = days.filter { it.toString() !in covered }
            if (missing.isEmpty()) continue
            val f =
                fetcher
                    ?: error(
                        "missing data for symbol $sym days $missing (no fetcher configured); supply a DataFetcher to DefaultDataStore",
                    )
            for (day in missing) {
                val target = TickDayFiles.fetchTarget(root, sym, day)
                f.fetch(sym, day, target)
                warnOnLowQuality(log, sym, day, target)
            }
            // Commit the freshly fetched days under the lock, coalescing onto a re-read of the
            // committed manifest (not the pre-fetch snapshot) so a concurrent writer's days survive.
            lock.withLock {
                val current = manifestStore.read(sym)
                var ranges = current.ranges
                for (day in missing) {
                    ranges = manifestStore.coalesce(ranges, DayRange(day.toString(), day.plusDays(1).toString()))
                }
                manifestStore.write(current.copy(ranges = ranges))
            }
        }
    }

    companion object {
        fun fromEnv(
            fetcher: DataFetcher? = null,
            clock: Clock = SystemClock(),
        ): DefaultDataStore = DefaultDataStore(root = DataRoot.resolve(), fetcher = fetcher, clock = clock)
    }
}
