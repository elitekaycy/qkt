package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.compare.SeriesCompare
import com.qkt.marketdata.ingest.store.DayPrices
import com.qkt.marketdata.ingest.store.DayTarget
import java.math.BigDecimal
import java.time.LocalDate

/** What an import does with the days it shares with data already stored, and what those days showed. */
data class OverlapPlan(
    val replace: List<LocalDate>,
    val keep: List<LocalDate>,
    /** The comparison of stored and incoming prices over the shared days, when there were any. */
    val evidence: String?,
)

/**
 * Decides, day by day, what happens where an import meets data already stored from another source. A day
 * is never spliced: it comes whole from one source. Before anything is decided the shared days are
 * compared minute by minute; a clock offset between the two means one of them is on the wrong timezone,
 * and the import refuses (unless the clock is trusted), because either choice would put prices at the
 * wrong time. Then, per [policy]: `fill` keeps the stored day unless the incoming one covers more hours of
 * it (so a source's partial first or last day never replaces a complete one), `replace` takes the incoming
 * day, `skip` keeps the stored one, `refuse` stops.
 */
internal class OverlapDecision(
    private val target: DayTarget,
    private val policy: OverlapPolicy,
    private val trustClock: Boolean,
    private val stepMs: Long,
    private val point: BigDecimal?,
) {
    fun decide(
        clashes: List<LocalDate>,
        incoming: (LocalDate) -> DayPrices,
    ): OverlapPlan {
        if (clashes.isEmpty()) return OverlapPlan(emptyList(), emptyList(), null)
        val stored = clashes.associateWith(target::readStored)
        val fresh = clashes.associateWith(incoming)
        val evidence = compare(clashes, stored, fresh)
        if (policy == OverlapPolicy.REFUSE) return OverlapPlan(emptyList(), emptyList(), evidence)
        val replace =
            clashes.filter { day ->
                when (policy) {
                    OverlapPolicy.REPLACE -> true
                    OverlapPolicy.SKIP -> false
                    else -> fresh.getValue(day).coveredHours > stored.getValue(day).coveredHours
                }
            }
        return OverlapPlan(replace, clashes - replace.toSet(), evidence)
    }

    private fun compare(
        days: List<LocalDate>,
        stored: Map<LocalDate, DayPrices>,
        fresh: Map<LocalDate, DayPrices>,
    ): String? {
        val a = days.flatMap { stored.getValue(it).minuteCandles(stepMs) }
        val b = days.flatMap { fresh.getValue(it).minuteCandles(stepMs) }
        if (a.isEmpty() || b.isEmpty()) return null
        val result = SeriesCompare.compare(a, b, stepMs, point)
        val summary = "stored vs incoming over ${days.size} shared day(s): ${result.summary()}"
        if (result.offsetMs != 0L && result.matched >= MIN_MATCHED && !trustClock) {
            throw ImportRefusal(
                "the days this import shares with stored data line up only when shifted by " +
                    "${result.offsetMs / MINUTE_MS} minutes: one of the two sources is on the wrong clock. " +
                    "Check --tz on this import (or the stored data's provenance); --trust-clock imports anyway",
                listOf(summary),
            )
        }
        return summary
    }

    private companion object {
        const val MIN_MATCHED = 30
        const val MINUTE_MS = 60_000L
    }
}
