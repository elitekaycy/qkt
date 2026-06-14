package com.qkt.marketdata.store.macro

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * One observation of a daily macro series: a value for a calendar date.
 * e.g. the 10y real yield `DFII10` on 2024-03-01 = 1.85.
 */
data class MacroPoint(
    val date: LocalDate,
    val value: BigDecimal,
)

/**
 * On-disk store for daily macro series under `<root>/macro/<SERIES>/<year>.csv`, one row per
 * published business day as `date,value` (ISO date). One file per year, written sorted; a write
 * merges into the existing year by date (new value wins) so incremental fetches are idempotent.
 *
 * Daily granularity, so unlike the tick store there is no per-day file — a year of a daily series
 * is small enough to hold in one file.
 */
class MacroSeriesStore(
    private val root: Path,
) {
    private fun yearFile(
        series: String,
        year: Int,
    ): Path = root.resolve("macro").resolve(series).resolve("$year.csv")

    /** Persist [points] for [series], grouped into per-year files, merging into any existing year. */
    fun write(
        series: String,
        points: List<MacroPoint>,
    ) {
        if (points.isEmpty()) return
        points.groupBy { it.date.year }.forEach { (year, yearPoints) ->
            val file = yearFile(series, year)
            Files.createDirectories(file.parent)
            val merged = (readYear(file) + yearPoints).associateBy { it.date } // later (new) wins
            val body =
                merged.values.sortedBy { it.date }.joinToString(
                    "\n",
                ) { "${it.date},${it.value.toPlainString()}" }
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, body + "\n")
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** All stored points for [series] with date in `[from, to]` inclusive, ascending by date. */
    fun read(
        series: String,
        from: LocalDate,
        to: LocalDate,
    ): List<MacroPoint> =
        (from.year..to.year)
            .flatMap { readYear(yearFile(series, it)) }
            .filter { it.date >= from && it.date <= to }
            .sortedBy { it.date }

    /**
     * True when the store brackets `[from, to]` — the earliest stored point is at or before [from]
     * and the latest at or after [to]. Gaps within (weekends/holidays) are expected and ignored;
     * this answers "do I still need to fetch the window?" for the provisioner.
     */
    fun hasRange(
        series: String,
        from: LocalDate,
        to: LocalDate,
    ): Boolean {
        val all = (from.year..to.year).flatMap { readYear(yearFile(series, it)) }
        val min = all.minByOrNull { it.date }?.date ?: return false
        val max = all.maxByOrNull { it.date }?.date ?: return false
        return min <= from && max >= to
    }

    private fun readYear(file: Path): List<MacroPoint> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split(",", limit = 2)
            MacroPoint(LocalDate.parse(parts[0]), BigDecimal(parts[1]))
        }
    }
}
