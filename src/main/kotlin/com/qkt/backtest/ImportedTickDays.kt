package com.qkt.backtest

import com.qkt.marketdata.ingest.store.ImportProvenance
import java.nio.file.Path
import java.time.LocalDate

/**
 * Which of a symbol's stored tick days came from `qkt data import`, read from the symbol directory's
 * provenance. The provisioner must never delete such a day to refetch it from another source (that would
 * swap the user's own data for a vendor's without a word), and a run whose window mixes imported days with
 * fetched ones says so.
 */
internal class ImportedTickDays(
    symbolDir: Path,
) {
    private val provenance = ImportProvenance.read(symbolDir)

    /** True when [day] was written by an import. */
    fun contains(day: LocalDate): Boolean = provenance.days.containsKey(day.toString())

    /**
     * The line telling a run that its window mixes sources, e.g. `XAUUSD ticks: 180 days imported (mt5-ticks),
     * 22 days from other sources`; null when every stored day has one origin.
     */
    fun mixLine(
        symbol: String,
        storedDays: List<LocalDate>,
    ): String? {
        val (imported, other) = storedDays.partition(::contains)
        if (imported.isEmpty() || other.isEmpty()) return null
        val presets = imported.mapNotNull { provenance.entryFor(it.toString())?.preset }.distinct().joinToString()
        return "$symbol ticks: ${imported.size} days imported ($presets), ${other.size} days from other sources"
    }
}
