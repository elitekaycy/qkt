package com.qkt.cli.data

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The days with no tick file between the first and last stored day: a hole a backtest would run straight
 * through. A store with ticks on any Saturday is a 24/7 market, so every day is expected; otherwise Monday
 * to Friday are (FX and metals keep a Sunday-evening file, and at most an empty Saturday one). Exchange holidays are not
 * known here, so a missing weekday may be one; it is listed for the reader to judge, never filled.
 */
internal class MissingDays private constructor(
    val days: List<LocalDate>,
    private val everyDay: Boolean,
) {
    /** One line per run of consecutive missing days. */
    fun lines(): List<String> {
        val runs = mutableListOf<MutableList<LocalDate>>()
        for (day in days) {
            val run = runs.lastOrNull()
            if (run != null && expectedBetween(run.last(), day).isEmpty()) run += day else runs += mutableListOf(day)
        }
        val kind = if (everyDay) "day" else "weekday"
        return runs.map { run ->
            val span = if (run.size == 1) "${run.first()}" else "${run.first()} … ${run.last()}"
            "$span  MISSING (${run.size} $kind(s) with no file between stored days: a hole or a holiday)"
        }
    }

    private fun expectedBetween(
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> =
        generateSequence(from.plusDays(1)) { it.plusDays(1) }
            .takeWhile { it < to }
            .filter { expected(it, everyDay) }
            .toList()

    companion object {
        /** The missing days among the stored day files, given as each day's tick count. */
        fun of(stored: Map<LocalDate, Int>): MissingDays {
            val present = stored.keys.toSortedSet()
            if (present.size < 2) return MissingDays(emptyList(), false)
            val everyDay = stored.any { (day, ticks) -> day.dayOfWeek == DayOfWeek.SATURDAY && ticks > 0 }
            val missing =
                generateSequence(present.first()) { it.plusDays(1) }
                    .takeWhile { it <= present.last() }
                    .filter { it !in present && expected(it, everyDay) }
                    .toList()
            return MissingDays(missing, everyDay)
        }

        private fun expected(
            day: LocalDate,
            everyDay: Boolean,
        ): Boolean = everyDay || day.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}
