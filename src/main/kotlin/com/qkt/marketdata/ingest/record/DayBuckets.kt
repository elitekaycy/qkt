package com.qkt.marketdata.ingest.record

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Spills records to one scratch file per UTC day under [dir], so an import of years of ticks holds one
 * day in memory at a time. [day] reads a day back sorted by time, then input order: the stable, total
 * order every later step relies on. Records land in the day of their own [IngestRecord.timeMs], so no
 * record can reach another day's store file.
 */
class DayBuckets(
    private val dir: Path,
) : AutoCloseable {
    private val open =
        object : LinkedHashMap<LocalDate, BufferedWriter>(MAX_OPEN, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, BufferedWriter>): Boolean {
                if (size <= MAX_OPEN) return false
                eldest.value.close()
                return true
            }
        }
    private val counts = sortedMapOf<LocalDate, Long>()

    /** Days holding at least one record, oldest first, with their record counts. */
    val days: Map<LocalDate, Long> get() = counts

    /** Adds [record] to its day. */
    fun add(record: IngestRecord) {
        val day = dayOf(record.timeMs)
        val writer =
            open.getOrPut(day) {
                Files.newBufferedWriter(file(day), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        writer.write(RecordCodec.encode(record))
        writer.newLine()
        counts[day] = (counts[day] ?: 0) + 1
    }

    /** [day]'s records sorted by time, ties by [orderKey] of their input order (identity: input order). */
    fun day(
        day: LocalDate,
        orderKey: (Long) -> Long = { it },
    ): List<IngestRecord> {
        open.remove(day)?.close()
        val file = file(day)
        if (!Files.exists(file)) return emptyList()
        val records = Files.readAllLines(file).map(RecordCodec::decode)
        return records.sortedWith(compareBy({ it.timeMs }, { orderKey(it.order) }))
    }

    override fun close() {
        open.values.forEach { it.close() }
        open.clear()
    }

    private fun file(day: LocalDate): Path = dir.resolve("$day.rec")

    companion object {
        private const val MAX_OPEN = 32
        private const val LOAD = 0.75f

        /** The UTC day of [epochMs]. */
        fun dayOf(epochMs: Long): LocalDate =
            Instant
                .ofEpochMilli(epochMs)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
    }
}
