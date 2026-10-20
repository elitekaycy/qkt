package com.qkt.backtest

import com.qkt.marketdata.store.CompletenessReport
import com.qkt.marketdata.store.DayCompleteness
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.dukascopy.DukascopyInstrument
import java.time.LocalDate

/**
 * The words a backtest refusing incomplete ticks prints: what window was asked for, where ticks were looked for
 * and which day files that folder holds, the days with no ticks and the days with a hole (each with the empty
 * stretch), whether built bars cover the window, and the commands that fix it.
 */
internal class IncompleteTickMessage(
    private val store: DefaultDataStore,
) {
    fun describe(
        stream: ProvisionStream,
        from: LocalDate,
        to: LocalDate,
        report: CompletenessReport,
        barsLine: String?,
        fetchEnabled: Boolean,
    ): String =
        buildString {
            val requested = report.days.count { it.status != DayCompleteness.Status.NON_TRADING }
            val covered = report.days.count { it.covered }
            val symbol = stream.bareSymbol
            val kind = if (covered == 0) "no" else "incomplete"
            append("$kind $symbol tick data for $from to $to ($covered of $requested trading days).")
            append("\n  Looked in: ${store.root.resolve("symbols").resolve(symbol)}")
            val span = heldSpanOf(symbol)
            if (span == null) append(" (empty — no tick days stored)") else append(" (day files for $span)")
            val missing = report.holes.filter { it.status == DayCompleteness.Status.MISSING }
            val incomplete = report.holes.filter { it.status == DayCompleteness.Status.INCOMPLETE }
            if (covered > 0 && missing.isNotEmpty()) {
                append(
                    "\n  Missing: ${collapseRanges(missing.map { it.day })}: no ticks stored for these trading days.",
                )
            }
            if (incomplete.isNotEmpty()) {
                append("\n  Incomplete: ")
                append(incomplete.joinToString("; ") { "${it.day} (${it.detail})" })
                append(".")
            }
            if (barsLine != null) append("\n  $barsLine")
            when {
                DukascopyInstrument.ofOrNull(symbol) == null ->
                    append("\n  qkt has no download source for $symbol: bring the data in a file (qkt data presets).")
                !fetchEnabled -> append("\n  Or let qkt download ticks: drop --no-fetch.")
            }
            append("\n  Or import your own: qkt data import <file> --as $symbol")
            append("\n  Or run on what exists anyway: --allow-incomplete (no prices, so no trades, across the gaps).")
        }

    /** Stored tick span from the manifest, e.g. "2026-08-24 to 2026-08-28"; null when empty. */
    private fun heldSpanOf(symbol: String): String? {
        val ranges = store.manifest(symbol).ranges
        if (ranges.isEmpty()) return null
        return ranges.joinToString(", ") { r ->
            val end = LocalDate.parse(r.to).minusDays(1)
            if (r.from == end.toString()) r.from else "${r.from} to $end"
        }
    }

    companion object {
        /** Collapse consecutive days into ranges: "2025-09-01 to 2025-09-05, 2025-09-07 (2 ranges, 6 days)". */
        fun collapseRanges(days: List<LocalDate>): String {
            if (days.isEmpty()) return "none"
            val sorted = days.sorted()
            val ranges = mutableListOf<Pair<LocalDate, LocalDate>>()
            var start = sorted[0]
            var prev = sorted[0]
            for (d in sorted.drop(1)) {
                if (d == prev.plusDays(1)) {
                    prev = d
                } else {
                    ranges += start to prev
                    start = d
                    prev = d
                }
            }
            ranges += start to prev
            val text = ranges.joinToString(", ") { (a, b) -> if (a == b) a.toString() else "$a to $b" }
            val rangeWord = if (ranges.size == 1) "1 range" else "${ranges.size} ranges"
            val dayWord = if (sorted.size == 1) "1 day" else "${sorted.size} days"
            return "$text ($rangeWord, $dayWord)"
        }
    }
}
