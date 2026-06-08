package com.qkt.marketdata.store.dukascopy

import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.store.DataFetcher
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream

/**
 * A [DataFetcher] that pulls one UTC day of ticks from dukascopy and writes the canonical
 * `symbols/<SYM>/<day>.csv.gz` file. Downloads all 24 hour files, decodes each, concatenates in
 * time order, and writes the standard 8-column tick CSV. A day with no data still writes a
 * header-only file, so the store records the day as fetched and does not retry it endlessly.
 */
class DukascopyTickFetcher(
    private val downloader: HourDownloader = OkHttpHourDownloader(),
) : DataFetcher {
    override fun fetch(
        symbol: String,
        day: LocalDate,
        target: Path,
    ) {
        val bare = symbol.substringAfter(':')
        val instrument = DukascopyInstrument.of(bare)
        val dayStartMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val ticks = ArrayList<Tick>()
        for (hour in 0..23) {
            val bi5 = downloader.download(instrument.dukascopyName, day, hour) ?: continue
            val decompressed = DukascopyTickDecoder.decompress(bi5)
            ticks +=
                DukascopyTickDecoder.decodeRecords(
                    decompressed = decompressed,
                    hourStartMs = dayStartMs + hour * 3_600_000L,
                    divisor = instrument.priceDivisor,
                    symbol = bare,
                )
        }
        ticks.sortBy { it.timestamp }
        write(target, ticks)
    }

    private fun write(
        target: Path,
        ticks: List<Tick>,
    ) {
        Files.createDirectories(target.parent)
        writer(target).use { w ->
            w.write(CsvTickFeed.EXPECTED_HEADER)
            w.newLine()
            for (t in ticks) {
                // timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume — price blank, mid derived.
                w.write("${t.timestamp},${t.symbol},,,${t.bid},${t.ask},${t.bidVolume},${t.askVolume}")
                w.newLine()
            }
        }
    }

    private fun writer(target: Path): BufferedWriter =
        BufferedWriter(OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(target)), Charsets.UTF_8))
}
