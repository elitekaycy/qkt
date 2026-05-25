package com.qkt.marketdata.store

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local bar store at `bars/{broker}/{symbol}/{timeframe}/{YYYY-MM-DD}.csv`.
 *
 * Written by `qkt fetch` (broker historical APIs). Read by backtests when the
 * requested (broker, symbol, timeframe, date) is present in the manifest — see
 * [com.qkt.marketdata.source.LocalBarMarketSource]. Each file holds one UTC
 * day of bars in CSV form:
 *
 * ```
 * timestamp,open,high,low,close,volume
 * 1704067200000,42500.00,42600.00,42400.00,42550.00,1234.5
 * ```
 *
 * Sibling to the tick store ([DefaultDataStore]); the two coexist. The bar
 * store is preferred when a backtest only needs bars at a known timeframe.
 */
class LocalBarStore(
    private val root: Path = DataRoot.resolve(),
    private val clock: Clock = SystemClock(),
) {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = false
        }

    fun dayFile(
        broker: String,
        symbol: String,
        timeframe: String,
        day: LocalDate,
    ): Path = barDir(broker, symbol, timeframe).resolve("$day.csv")

    fun hasDay(
        broker: String,
        symbol: String,
        timeframe: String,
        day: LocalDate,
    ): Boolean = Files.isRegularFile(dayFile(broker, symbol, timeframe, day))

    fun writeDay(
        broker: String,
        symbol: String,
        timeframe: String,
        day: LocalDate,
        bars: List<Candle>,
    ) {
        val dir = barDir(broker, symbol, timeframe)
        Files.createDirectories(dir)
        val target = dir.resolve("$day.csv")
        val tmp = dir.resolve("$day.csv.tmp")
        val sb = StringBuilder()
        sb.append("timestamp,open,high,low,close,volume\n")
        for (b in bars.sortedBy { it.startTime }) {
            sb.append(b.startTime).append(',')
            sb.append(b.open.toPlainString()).append(',')
            sb.append(b.high.toPlainString()).append(',')
            sb.append(b.low.toPlainString()).append(',')
            sb.append(b.close.toPlainString()).append(',')
            sb.append(b.volume.toPlainString()).append('\n')
        }
        Files.writeString(tmp, sb.toString())
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun readDay(
        broker: String,
        symbol: String,
        timeframe: String,
        day: LocalDate,
    ): List<Candle> {
        val path = dayFile(broker, symbol, timeframe, day)
        if (!Files.isRegularFile(path)) return emptyList()
        val qktSymbol = "$broker:$symbol"
        val windowMs =
            com.qkt.candles.TimeWindow
                .parse(timeframe)
                .durationMs
        val out = mutableListOf<Candle>()
        Files.newBufferedReader(path).use { reader ->
            var line = reader.readLine() ?: return emptyList()
            // Skip header
            line = reader.readLine()
            while (line != null) {
                val parts = line.split(',')
                if (parts.size >= 6) {
                    val ts = parts[0].toLong()
                    out.add(
                        Candle(
                            symbol = qktSymbol,
                            open = BigDecimal(parts[1]),
                            high = BigDecimal(parts[2]),
                            low = BigDecimal(parts[3]),
                            close = BigDecimal(parts[4]),
                            volume = BigDecimal(parts[5]),
                            startTime = ts,
                            endTime = ts + windowMs,
                        ),
                    )
                }
                line = reader.readLine()
            }
        }
        return out
    }

    fun readManifest(
        broker: String,
        symbol: String,
        timeframe: String,
    ): BarsManifest {
        val path = manifestPath(broker, symbol, timeframe)
        if (!Files.exists(path)) {
            return BarsManifest(broker = broker, symbol = symbol, timeframe = timeframe)
        }
        val text = Files.readString(path)
        val manifest =
            try {
                json.decodeFromString<BarsManifest>(text)
            } catch (e: Exception) {
                error("corrupt bars manifest at $path: ${e.message}")
            }
        require(manifest.schemaVersion == 1) {
            "unsupported bars manifest schemaVersion at $path: ${manifest.schemaVersion}"
        }
        require(manifest.schema == "qkt-bars-csv-v1") {
            "unsupported bars manifest schema at $path: ${manifest.schema}"
        }
        return manifest
    }

    fun writeManifest(manifest: BarsManifest) {
        val dir = barDir(manifest.broker, manifest.symbol, manifest.timeframe)
        Files.createDirectories(dir)
        val target = dir.resolve("manifest.json")
        val tmp = dir.resolve("manifest.json.tmp")
        val updated = manifest.copy(lastUpdated = Instant.ofEpochMilli(clock.now()).toString())
        Files.writeString(tmp, json.encodeToString(updated))
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Coalesce a new day-range into the manifest's existing ranges, merging
     * adjacent or overlapping spans. Adjacency uses calendar-day math (Jan-15
     * and Jan-16 merge), not lexicographic string compare.
     */
    fun coalesce(
        existing: List<DayRange>,
        added: DayRange,
    ): List<DayRange> {
        val all = (existing + added).sortedBy { it.from }
        val merged = mutableListOf<DayRange>()
        for (range in all) {
            val last = merged.lastOrNull()
            if (last != null) {
                val lastTo = LocalDate.parse(last.to)
                val rangeFrom = LocalDate.parse(range.from)
                if (!lastTo.plusDays(1).isBefore(rangeFrom)) {
                    val rangeTo = LocalDate.parse(range.to)
                    val newTo = if (lastTo.isAfter(rangeTo)) lastTo else rangeTo
                    merged[merged.size - 1] = DayRange(last.from, newTo.toString())
                    continue
                }
            }
            merged.add(range)
        }
        return merged
    }

    /** Records that [day] is now on disk, idempotently. */
    fun recordDay(
        broker: String,
        symbol: String,
        timeframe: String,
        day: LocalDate,
    ) {
        val existing = readManifest(broker, symbol, timeframe)
        val newRange = DayRange(day.toString(), day.toString())
        val merged = coalesce(existing.ranges, newRange)
        writeManifest(existing.copy(ranges = merged))
    }

    private fun barDir(
        broker: String,
        symbol: String,
        timeframe: String,
    ): Path = root.resolve("bars").resolve(broker).resolve(symbol).resolve(timeframe)

    private fun manifestPath(
        broker: String,
        symbol: String,
        timeframe: String,
    ): Path = barDir(broker, symbol, timeframe).resolve("manifest.json")

    companion object {
        /** UTC day of an epoch-ms instant. Used by the writer to group fetched bars per day. */
        fun dayOf(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
    }
}
