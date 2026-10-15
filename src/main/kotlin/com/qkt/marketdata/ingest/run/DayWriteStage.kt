package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.store.DayTarget
import com.qkt.marketdata.ingest.store.ImportEntry
import com.qkt.marketdata.ingest.store.ImportProvenance
import java.time.LocalDate

/**
 * Writes a validated import day by day into a [DayTarget]. Every decision is made before the first write:
 * a day already stored by this same import is unchanged; one stored otherwise follows the overlap policy
 * (refusing lists every such day, so nothing is half-done). Days inside the imported span that had no rows
 * are written empty only where the store has nothing (bars), never over stored data. Each written day is
 * read back by [verify] and the manifest and provenance are updated last.
 */
internal class DayWriteStage(
    private val target: DayTarget,
    private val validated: ValidateStage,
    private val read: ReadResult,
    private val policy: OverlapPolicy,
    private val fillEmpty: Boolean,
    private val stamp: String,
) {
    fun write(
        entry: ImportEntry,
        verify: (LocalDate, Int) -> Unit,
    ): DayOutcomes {
        val provenance = ImportProvenance.read(target.dir)
        val withData = read.buckets.days.keys
        val span = if (withData.isEmpty()) emptyList() else daysBetween(withData.first(), withData.last())
        val empties = if (fillEmpty) span.filter { it !in withData && target.stored(it).isEmpty() } else emptyList()
        val unchanged = mutableListOf<LocalDate>()
        val clashes = mutableListOf<LocalDate>()
        val fresh = mutableListOf<LocalDate>()
        for (day in withData) {
            when {
                target.stored(day).isEmpty() -> fresh += day
                provenance.days[day.toString()] == entry.id -> unchanged += day
                else -> clashes += day
            }
        }
        if (clashes.isNotEmpty() && policy == OverlapPolicy.REFUSE) {
            throw ImportRefusal(
                "${clashes.size} day(s) are already stored in ${target.dir}: pass --on-overlap skip to keep them or " +
                    "--on-overlap replace to replace them (the stored files are moved to ${DayTarget.BACKUP}/)",
                clashes.map { day -> "$day (${origin(provenance, day)})" },
            )
        }
        val replaced = if (policy == OverlapPolicy.REPLACE) clashes.toList() else emptyList()
        val skipped = if (policy == OverlapPolicy.SKIP) clashes.toList() else emptyList()
        replaced.forEach { target.backup(it, stamp) }
        for (day in fresh + replaced) {
            val records = validated.clean(day)
            target.write(day, records)
            verify(day, records.size)
        }
        for (day in empties) {
            target.write(day, emptyList())
            verify(day, 0)
        }
        val touched = (fresh + replaced + empties).sorted()
        target.commit(touched)
        if (touched.isNotEmpty()) ImportProvenance.record(target.dir, entry, touched.map { it.toString() })
        return DayOutcomes(fresh.sorted(), unchanged, skipped, replaced, empties)
    }

    private fun origin(
        provenance: ImportProvenance,
        day: LocalDate,
    ): String =
        provenance.entryFor(day.toString())?.let { "imported by ${it.preset}" }
            ?: "not imported: fetched or hand-written"

    private fun daysBetween(
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
}
