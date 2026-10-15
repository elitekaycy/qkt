package com.qkt.marketdata.ingest.run

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.check.CheckTally
import com.qkt.marketdata.ingest.check.DayChecks
import com.qkt.marketdata.ingest.check.QuoteBarPairing
import com.qkt.marketdata.ingest.check.SpikeFlags
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.record.IngestRecord
import java.time.LocalDate

/**
 * Validates every day of a read import before anything is written, and fixes the bar timeframe: the
 * stated one, or the most common spacing between consecutive bars, which must be a timeframe qkt can
 * name. [clean] gives the records a day is written with, identical to what was checked.
 */
internal class ValidateStage(
    private val read: ReadResult,
    private val kind: IngestKind,
    declaredTimeframe: String?,
    keepLast: Boolean = false,
) {
    val tally = CheckTally()

    /** The bar timeframe (`1m`, `1h`, `1d`), or null for kinds without one. */
    val timeframe: TimeWindow? =
        when (kind) {
            IngestKind.BARS -> declaredTimeframe?.let(TimeWindow::parse) ?: inferTimeframe()
            IngestKind.SERIES -> TimeWindow.parse("1d")
            else -> null
        }

    private val gridMs = timeframe?.durationMs?.takeIf { kind == IngestKind.BARS }
    private val checks = DayChecks(tally, gridMs, keepLast)
    private val sides = read.stats.mapNotNull { s -> QuoteBarPairing.sideOf(s.name)?.let { s.index to it } }.toMap()
    private val pairing = QuoteBarPairing(if (kind == IngestKind.BARS) sides else emptyMap(), tally)
    private val writePairing = QuoteBarPairing(if (kind == IngestKind.BARS) sides else emptyMap(), CheckTally())

    /** True when bid and ask bar files were joined into mid bars. */
    val pairedQuotes: Boolean get() = pairing.active

    /** Re-cleans for writing with its own tally, so a day written after validation is not counted twice. */
    private val writeChecks = DayChecks(CheckTally(), gridMs, keepLast)

    /** Checks every day; refuses with the problems found, if any. */
    fun validate() {
        for (day in read.buckets.days.keys) {
            val records = checks.clean(day, pairing.join(day, read.buckets.day(day, read::orderKey), ::inputOf))
            if (kind == IngestKind.BARS || kind == IngestKind.TICKS) SpikeFlags.scan(day, records, tally)
        }
        if (tally.problems.isNotEmpty()) {
            throw ImportRefusal("${tally.problems.size} problem(s) in the data; nothing was written", tally.problems)
        }
    }

    /** [day]'s records, sorted and cleaned exactly as validation cleaned them. */
    fun clean(day: LocalDate): List<IngestRecord> =
        writeChecks.clean(day, writePairing.join(day, read.buckets.day(day, read::orderKey), ::inputOf))

    private fun inputOf(order: Long): Int = (order ushr InputPass.LINE_BITS).toInt()

    private fun inferTimeframe(): TimeWindow {
        val spacing = mutableMapOf<Long, Int>()
        var previous: Long? = null
        for (day in read.buckets.days.keys) {
            for (r in read.buckets.day(day, read::orderKey)) {
                previous?.let { p -> (r.timeMs - p).takeIf { it > 0 }?.let { spacing[it] = (spacing[it] ?: 0) + 1 } }
                previous = r.timeMs
            }
        }
        val modal =
            spacing.maxByOrNull { it.value }?.key
                ?: throw ImportRefusal("cannot tell the bar timeframe from fewer than two bars; pass --tf")
        val window =
            try {
                TimeWindow.parse("${modal / SECOND_MS}s")
            } catch (e: IllegalArgumentException) {
                throw ImportRefusal(
                    "bars are mostly ${modal}ms apart, which is not a timeframe; pass --tf (${e.message})",
                )
            }
        if (modal % SECOND_MS !=
            0L
        ) {
            throw ImportRefusal(
                "bars are mostly ${modal}ms apart, which is not a whole number of seconds; pass --tf",
            )
        }
        return TimeWindow.parse(window.canonicalSpec())
    }

    private companion object {
        const val SECOND_MS = 1000L
    }
}
