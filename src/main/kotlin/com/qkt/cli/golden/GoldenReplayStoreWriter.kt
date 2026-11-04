package com.qkt.cli.golden

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.DayRange
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.Manifest
import com.qkt.marketdata.store.ManifestStore
import com.qkt.marketdata.store.TickStoreKeys
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Writes golden ticks and bars into the normal QKT tick store and CSV/binary bar stores under a root. */
internal class GoldenReplayStoreWriter(
    private val clock: Clock,
) {
    /**
     * Write each broker symbol's ticks into its venue's store (`ticks/<LABEL>/<SYMBOL>/`), one UTC-day CSV
     * per day plus a manifest, and into the bare-symbol store (`symbols/<SYMBOL>/`) too when no other venue
     * quotes that bare symbol, so a data root read by bare symbol keeps working.
     */
    fun writeTickStore(
        root: Path,
        records: List<RecordedTick>,
    ) {
        val bySymbol = records.groupBy { it.tick.symbol }
        val venuesByBare = bySymbol.keys.groupBy { it.substringAfter(':') }
        for ((symbol, symbolRecords) in bySymbol) {
            val bare = symbol.substringAfter(':')
            if (':' in symbol) writeTickDays(root, symbol, bare, symbolRecords)
            if (venuesByBare.getValue(bare).size == 1) writeTickDays(root, bare, bare, symbolRecords)
        }
    }

    private fun writeTickDays(
        root: Path,
        key: String,
        bare: String,
        records: List<RecordedTick>,
    ) {
        val dir = TickStoreKeys.dir(root, key)
        Files.createDirectories(dir)
        makePrivateDirectory(dir)
        val byDay = records.groupBy { utcDay(it.tick.timestamp) }.toSortedMap()
        for ((day, dayRecords) in byDay) {
            val text =
                buildString {
                    append("timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume\n")
                    for (record in dayRecords) {
                        val tick = record.tick
                        append(tick.timestamp).append(',').append(bare).append(',')
                        append(tick.price.toPlainString()).append(',')
                        append(tick.volume?.toPlainString().orEmpty()).append(',')
                        append(tick.bid?.toPlainString().orEmpty()).append(',')
                        append(tick.ask?.toPlainString().orEmpty()).append(',')
                        append(tick.bidVolume?.toPlainString().orEmpty()).append(',')
                        append(tick.askVolume?.toPlainString().orEmpty()).append('\n')
                    }
                }
            val file = dir.resolve("$day.csv")
            Files.writeString(file, text, StandardCharsets.UTF_8)
            makePrivateFile(file)
        }
        val ranges = byDay.keys.map { DayRange(it.toString(), it.plusDays(1).toString()) }
        ManifestStore(root, clock).write(Manifest(symbol = key, ranges = ranges))
    }

    /** Write each bar into both bar stores, grouped by broker, symbol, window and UTC day. */
    fun writeBarStores(
        root: Path,
        records: List<RecordedCandle>,
    ) {
        val csv = LocalBarStore(root, clock)
        val binary = BinaryBarStore(root)
        val grouped =
            records.groupBy { record ->
                val candle = record.candle
                val (broker, bare) = splitSymbol(candle.symbol)
                val window = TimeWindow(candle.endTime - candle.startTime)
                require(window.durationMs % 1_000L == 0L) {
                    "unsupported sub-second candle window: ${window.durationMs}ms"
                }
                BarKey(broker, bare, window)
            }
        for ((key, keyRecords) in grouped) {
            val byDay = keyRecords.map { it.candle }.groupBy { utcDay(it.startTime) }.toSortedMap()
            for ((day, candles) in byDay) {
                csv.writeDay(key.broker, key.symbol, key.window.canonicalSpec(), day, candles)
                csv.recordDay(key.broker, key.symbol, key.window.canonicalSpec(), day)
                binary.writeDay(key.broker, key.symbol, key.window, day, candles)
            }
        }
    }

    private fun utcDay(timestampMs: Long): LocalDate =
        Instant.ofEpochMilli(timestampMs).atZone(ZoneOffset.UTC).toLocalDate()

    private data class BarKey(
        val broker: String,
        val symbol: String,
        val window: TimeWindow,
    )
}
