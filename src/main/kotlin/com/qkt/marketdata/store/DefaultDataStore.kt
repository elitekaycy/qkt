package com.qkt.marketdata.store

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.marketdata.ConcatenatedTickFeed
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.RangeClippedTickFeed
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DefaultDataStore(
    override val root: Path,
    private val fetcher: DataFetcher? = null,
    private val clock: Clock = SystemClock(),
    private val manifestStore: ManifestStore = ManifestStore(root, clock),
) : DataStore {
    override fun manifest(symbol: String): Manifest = manifestStore.read(symbol)

    override fun dayFile(
        symbol: String,
        day: LocalDate,
    ): Path? {
        val symDir = root.resolve("symbols").resolve(symbol)
        val gz = symDir.resolve("$day.csv.gz")
        val flat = symDir.resolve("$day.csv")
        return when {
            Files.exists(gz) -> gz
            Files.exists(flat) -> flat
            else -> null
        }
    }

    override fun openFeed(request: MarketRequest): TickFeed {
        val (fromMs, toMs) = resolveRange(request)
        materializeMissing(request.symbols, fromMs, toMs)

        val perSymbol =
            request.symbols.map { sym ->
                val days = daysCovering(fromMs, toMs)
                val factories: List<() -> TickFeed> =
                    days.mapNotNull { dayFile(sym, it) }.map { path -> { CsvTickFeed(path) } }
                ConcatenatedTickFeed(factories)
            }
        val merged: TickFeed = if (perSymbol.size == 1) perSymbol[0] else MergingTickFeed(perSymbol)
        return RangeClippedTickFeed(merged, fromMs = fromMs, toMs = toMs)
    }

    override fun prefetch(request: MarketRequest) {
        val (fromMs, toMs) = resolveRange(request)
        materializeMissing(request.symbols, fromMs, toMs)
    }

    override fun rebuildManifests() {
        val symbolsDir = root.resolve("symbols")
        if (!Files.exists(symbolsDir)) return
        Files.list(symbolsDir).use { stream ->
            for (symDir in stream) {
                if (!Files.isDirectory(symDir)) continue
                val sym = symDir.fileName.toString()
                val days =
                    Files.list(symDir).use { fs ->
                        fs
                            .map { it.fileName.toString() }
                            .filter { it.endsWith(".csv") || it.endsWith(".csv.gz") }
                            .map { it.removeSuffix(".gz").removeSuffix(".csv") }
                            .distinct()
                            .sorted()
                            .toList()
                    }
                if (days.isEmpty()) continue
                val ranges = mutableListOf<DayRange>()
                var rangeStart: String? = null
                var rangeEnd: String? = null
                for (day in days) {
                    val date = LocalDate.parse(day)
                    if (rangeStart == null) {
                        rangeStart = day
                        rangeEnd = date.plusDays(1).toString()
                    } else if (rangeEnd == day) {
                        rangeEnd = date.plusDays(1).toString()
                    } else {
                        ranges.add(DayRange(rangeStart, rangeEnd!!))
                        rangeStart = day
                        rangeEnd = date.plusDays(1).toString()
                    }
                }
                if (rangeStart != null) ranges.add(DayRange(rangeStart, rangeEnd!!))
                manifestStore.write(Manifest(symbol = sym, ranges = ranges))
            }
        }
    }

    private fun resolveRange(request: MarketRequest): Pair<Long, Long> {
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
                val target = root.resolve("symbols").resolve(sym).resolve("$day.csv.gz")
                f.fetch(sym, day, target)
            }
            var ranges = manifest.ranges
            for (day in missing) {
                ranges = manifestStore.coalesce(ranges, DayRange(day.toString(), day.plusDays(1).toString()))
            }
            manifestStore.write(manifest.copy(ranges = ranges))
        }
    }

    private fun daysCovering(
        fromMs: Long,
        toMs: Long,
    ): List<LocalDate> {
        val fromDay = Instant.ofEpochMilli(fromMs).atZone(ZoneOffset.UTC).toLocalDate()
        val toInclusiveDay = Instant.ofEpochMilli(toMs - 1).atZone(ZoneOffset.UTC).toLocalDate()
        val days = mutableListOf<LocalDate>()
        var d = fromDay
        while (!d.isAfter(toInclusiveDay)) {
            days.add(d)
            d = d.plusDays(1)
        }
        return days
    }

    private fun dayList(range: DayRange): List<String> {
        val days = mutableListOf<String>()
        var d = LocalDate.parse(range.from)
        val end = LocalDate.parse(range.to)
        while (d.isBefore(end)) {
            days.add(d.toString())
            d = d.plusDays(1)
        }
        return days
    }

    companion object {
        fun fromEnv(
            fetcher: DataFetcher? = null,
            clock: Clock = SystemClock(),
        ): DefaultDataStore = DefaultDataStore(root = DataRoot.resolve(), fetcher = fetcher, clock = clock)
    }
}
