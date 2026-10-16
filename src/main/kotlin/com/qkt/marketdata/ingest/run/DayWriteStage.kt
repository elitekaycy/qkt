package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.store.DayPrices
import com.qkt.marketdata.ingest.store.DayTarget
import com.qkt.marketdata.ingest.store.ImportEntry
import com.qkt.marketdata.ingest.store.ImportProvenance
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Writes a validated import day by day into a [DayTarget]. Every decision is made before the first write:
 * a day already stored by this same import is unchanged; a day stored from elsewhere goes through
 * [OverlapDecision] (compared with the incoming one first, then kept or replaced whole, never spliced).
 * Days inside the imported span that had no rows are written empty only where the store has nothing
 * (bars), never over stored data. Each written day is read back by [verify]; the manifest and provenance
 * are updated last, and every switch of source in the resulting series is reported ([SeamReport]).
 */
internal class DayWriteStage(
    private val target: DayTarget,
    private val validated: ValidateStage,
    private val read: ReadResult,
    private val policy: OverlapPolicy,
    private val fillEmpty: Boolean,
    private val stamp: String,
    private val trustClock: Boolean = false,
    private val stepMs: Long = MINUTE_MS,
    private val point: BigDecimal? = null,
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
        val decision = OverlapDecision(target, policy, trustClock, stepMs, point)
        val plan = decision.decide(clashes) { DayPrices.of(validated.clean(it)) }
        if (clashes.isNotEmpty() && policy == OverlapPolicy.REFUSE) {
            throw ImportRefusal(
                "${clashes.size} day(s) are already stored in ${target.dir}: pass --on-overlap fill (keep the fuller " +
                    "day), skip, or replace (the stored files are moved to ${DayTarget.BACKUP}/)",
                clashes.map { day -> "$day (${origin(provenance, day)})" } + listOfNotNull(plan.evidence),
            )
        }
        plan.replace.forEach { target.backup(it, stamp) }
        val writtenPrices = mutableMapOf<LocalDate, DayPrices>()
        for (day in fresh + plan.replace) {
            val records = validated.clean(day)
            target.write(day, records)
            verify(day, records.size)
            writtenPrices[day] = DayPrices.of(records)
        }
        for (day in empties) {
            target.write(day, emptyList())
            verify(day, 0)
        }
        val touched = (fresh + plan.replace + empties).sorted()
        target.commit(touched)
        if (touched.isNotEmpty()) ImportProvenance.record(target.dir, entry, touched.map { it.toString() })
        val seams =
            SeamReport(
                target,
                point,
            ).seams(writtenPrices, "this import (${entry.preset})", provenance, entry.id)
        val skipped = if (policy == OverlapPolicy.SKIP) plan.keep else emptyList()
        val kept = if (policy == OverlapPolicy.SKIP) emptyList() else plan.keep
        return DayOutcomes(fresh.sorted(), unchanged, skipped, plan.replace, empties, kept, plan.evidence, seams)
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

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
