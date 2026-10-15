package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.store.DayPrices
import com.qkt.marketdata.ingest.store.DayTarget
import com.qkt.marketdata.ingest.store.ImportProvenance
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

/**
 * Lists every place where a series switches source once an import is written: the two days, the two
 * sources, and the price gap across the switch (the next source's first price minus the previous one's
 * last, in points when known). Two vendors never quote identical prices, so a switch is a step in the
 * series a strategy will see; the report makes each step visible instead of hiding it in the data.
 */
internal class SeamReport(
    private val target: DayTarget,
    private val point: BigDecimal?,
) {
    /** The seams around [written] (this import's days, labelled [label]) among the stored days nearby. */
    fun seams(
        written: Map<LocalDate, DayPrices>,
        label: String,
        provenance: ImportProvenance,
        importId: String,
    ): List<String> {
        if (written.isEmpty()) return emptyList()
        val from = written.keys.min().minusDays(NEIGHBOUR_DAYS)
        val to = written.keys.max().plusDays(NEIGHBOUR_DAYS)
        val days =
            generateSequence(from) { it.plusDays(1) }
                .takeWhile { !it.isAfter(to) }
                .mapNotNull { day -> source(day, written, label, provenance, importId)?.let { day to it } }
                .toList()
        return days.zipWithNext().filter { (a, b) -> a.second != b.second }.map { (a, b) ->
            val gap = gapBetween(prices(a.first, written), prices(b.first, written))
            "${a.first} → ${b.first}: ${a.second} → ${b.second}" + (gap?.let { ", price step $it" } ?: "")
        }
    }

    private fun source(
        day: LocalDate,
        written: Map<LocalDate, DayPrices>,
        label: String,
        provenance: ImportProvenance,
        importId: String,
    ): String? {
        if (day in written || provenance.days[day.toString()] == importId) return label
        if (target.stored(day).isEmpty() || target.readStored(day).points.isEmpty()) return null
        return provenance.entryFor(day.toString())?.preset?.let { "imported $it" }
            ?: "data not imported (fetched or hand-written)"
    }

    private fun prices(
        day: LocalDate,
        written: Map<LocalDate, DayPrices>,
    ): DayPrices = written[day] ?: target.readStored(day)

    private fun gapBetween(
        before: DayPrices,
        after: DayPrices,
    ): String? {
        val last = before.last?.price ?: return null
        val first = after.first?.price ?: return null
        val diff = first.subtract(last)
        val p = point ?: return diff.stripTrailingZeros().toPlainString()
        return "${diff.divide(p, MathContext.DECIMAL64).stripTrailingZeros().toPlainString()} pt"
    }

    private companion object {
        const val NEIGHBOUR_DAYS = 7L
    }
}
