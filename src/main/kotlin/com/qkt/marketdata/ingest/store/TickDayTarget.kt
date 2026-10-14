package com.qkt.marketdata.ingest.store

import com.qkt.common.Clock
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.ingest.record.IngestRecord
import com.qkt.marketdata.ingest.record.TickRecord
import com.qkt.marketdata.store.DayRange
import com.qkt.marketdata.store.ManifestLock
import com.qkt.marketdata.store.ManifestStore
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.zip.GZIPOutputStream

/**
 * Writes imported ticks as qkt tick-store day files, `symbols/<BARE>/<day>.csv.gz` with the exact
 * `timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume` header [CsvTickFeed] requires, and records
 * the days in the symbol's manifest under the store's [ManifestLock], as the Dukascopy fetcher does.
 * A quote row leaves `price` blank (qkt derives the mid); a trade row leaves `bid` and `ask` blank. The
 * gzip header carries no timestamp, so the same ticks always give the same bytes.
 */
class TickDayTarget(
    private val root: Path,
    private val bare: String,
    private val clock: Clock,
) : DayTarget {
    override val dir: Path = root.resolve("symbols").resolve(bare)

    override fun stored(day: LocalDate): List<Path> =
        listOf("$day.bin", "$day.csv.gz", "$day.csv").map(dir::resolve).filter(Files::exists)

    override fun write(
        day: LocalDate,
        records: List<IngestRecord>,
    ) {
        Files.createDirectories(dir)
        val target = dir.resolve("$day.csv.gz")
        val tmp = Files.createTempFile(dir, ".$day", ".csv.gz.tmp")
        GZIPOutputStream(Files.newOutputStream(tmp)).bufferedWriter(Charsets.UTF_8).use { out ->
            out.write(CsvTickFeed.EXPECTED_HEADER)
            out.write("\n")
            for (r in records) {
                val t = r as TickRecord
                val cells =
                    listOf(
                        t.price,
                        t.volume,
                        t.bid,
                        t.ask,
                        t.bidVolume,
                        t.askVolume,
                    ).joinToString(",", transform = ::c)
                out.write("${t.timeMs},$bare,$cells\n")
            }
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun commit(days: Collection<LocalDate>) {
        if (days.isEmpty()) return
        val manifests = ManifestStore(root, clock)
        ManifestLock(root).withLock {
            val current = manifests.read(bare)
            var ranges = current.ranges
            for (day in days.sorted()) {
                ranges =
                    manifests.coalesce(ranges, DayRange(day.toString(), day.plusDays(1).toString()))
            }
            manifests.write(current.copy(ranges = ranges))
        }
    }

    private fun c(v: BigDecimal?): String = v?.toPlainString().orEmpty()
}
