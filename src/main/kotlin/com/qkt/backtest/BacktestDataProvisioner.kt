package com.qkt.backtest

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DayCompleteness
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.TickCompletenessValidator
import java.time.LocalDate
import java.time.ZoneOffset

/** A symbol the backtest needs data for. [bareSymbol] has no `NAME:` prefix (e.g. `XAUUSD`). */
data class ProvisionStream(
    val broker: String,
    val bareSymbol: String,
)

/** Thrown when, after fetching, the data still has holes and the caller did not allow incompleteness. */
class IncompleteDataException(
    message: String,
) : RuntimeException(message)

/**
 * Makes the local tick store complete for a backtest before it runs: fetch missing days (via the
 * store's configured fetcher), validate session-hour coverage against the trading calendar, repair
 * any incomplete day once (delete + refetch), then fail loud on a remaining hole unless allowed.
 *
 * Operates on **bare** symbols, matching how `LocalMarketSource` keys the tick store.
 */
class BacktestDataProvisioner(
    private val store: DefaultDataStore,
) {
    /** A fetch that fails after its retries is incomplete data, reported like any other hole. */
    private fun prefetch(
        request: MarketRequest,
        symbol: String,
    ) {
        try {
            store.prefetch(request)
        } catch (e: java.io.IOException) {
            throw IncompleteDataException("could not fetch ticks for $symbol: ${e.message}; rerun later")
        } catch (e: IllegalStateException) {
            throw IncompleteDataException("could not fetch ticks for $symbol: ${e.message}")
        }
    }

    fun ensure(
        streams: List<ProvisionStream>,
        from: LocalDate,
        to: LocalDate,
        fetchEnabled: Boolean,
        allowIncomplete: Boolean,
        calendarFor: (String) -> TradingCalendar,
        /** One-line bars suggestion for the missing-data error (e.g. "Bars exist ... rerun with --bars"); null omits it. */
        barsLineFor: (ProvisionStream) -> String? = { null },
    ) {
        val fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant()
        val toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

        for (s in streams.distinctBy { it.bareSymbol }) {
            val request = MarketRequest(symbols = listOf(s.bareSymbol), from = fromInstant, to = toInstant)
            if (fetchEnabled) prefetch(request, s.bareSymbol)

            var report = TickCompletenessValidator.validate(store, s.bareSymbol, from, to, calendarFor(s.bareSymbol))

            if (report.hasHoles && fetchEnabled) {
                // A prior interrupted fetch can leave a day partial. Delete + refetch those once.
                for (hole in report.holes) store.dropDay(s.bareSymbol, hole.day)
                prefetch(request, s.bareSymbol)
                report = TickCompletenessValidator.validate(store, s.bareSymbol, from, to, calendarFor(s.bareSymbol))
            }

            val requested = report.days.count { it.status != DayCompleteness.Status.NON_TRADING }
            val covered = report.days.count { it.status == DayCompleteness.Status.COMPLETE }
            System.err.println("qkt: tick coverage ${report.symbol} $covered/$requested trading days")

            if (report.hasHoles && !allowIncomplete) {
                throw IncompleteDataException(
                    describe(
                        stream = s,
                        from = from,
                        to = to,
                        covered = covered,
                        requested = requested,
                        holeDays = report.holes.map { it.day },
                        barsLine = barsLineFor(s),
                        fetchEnabled = fetchEnabled,
                    ),
                )
            }
            if (report.hasHoles) {
                System.err.println(
                    "qkt: WARNING — running with incomplete data: ${collapseRanges(report.holes.map { it.day })}",
                )
            }
        }
    }

    /**
     * The missing-data error: what window was asked for, where ticks were looked for and what
     * that folder holds, the holes as compact ranges, whether built bars cover the window, and
     * which flag unblocks the run. Exit code and `--allow-incomplete` behavior are unchanged.
     */
    private fun describe(
        stream: ProvisionStream,
        from: LocalDate,
        to: LocalDate,
        covered: Int,
        requested: Int,
        holeDays: List<LocalDate>,
        barsLine: String?,
        fetchEnabled: Boolean,
    ): String =
        buildString {
            if (covered == 0) {
                append("no ${stream.bareSymbol} tick data for $from to $to ($covered of $requested trading days).")
            } else {
                append("incomplete ${stream.bareSymbol} tick data for $from to $to ($covered of $requested trading days).")
            }
            append("\n  Looked in: ${store.root.resolve("symbols").resolve(stream.bareSymbol)}")
            val span = heldSpanOf(stream.bareSymbol)
            if (span == null) append(" (empty — no tick days stored)") else append(" (has $span)")
            if (covered > 0) {
                append("\n  Missing: ${collapseRanges(holeDays)}.")
            }
            if (barsLine != null) append("\n  $barsLine")
            if (!fetchEnabled) append("\n  Or let qkt download ticks: drop --no-fetch.")
            append("\n  Or run on what exists anyway: --allow-incomplete.")
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

    /** Collapse consecutive days into ranges: "2025-09-01 to 2025-09-05, 2025-09-07 (2 ranges, 6 days)". */
    private fun collapseRanges(days: List<LocalDate>): String {
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
