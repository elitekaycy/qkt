package com.qkt.backtest

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DayCompleteness
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.TickCompletenessValidator
import com.qkt.marketdata.store.dayList
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
 * store's configured fetcher), validate session coverage against the trading calendar (the rule in
 * [com.qkt.marketdata.store.SessionCoverage]: holidays need no ticks, holiday short sessions pass), repair
 * any incomplete day once (delete + refetch), then fail loud on a remaining hole unless allowed.
 *
 * Operates on **bare** symbols, matching how `LocalMarketSource` keys the tick store.
 */
class BacktestDataProvisioner(
    private val store: DefaultDataStore,
    /** Where status lines go, e.g. `qkt: fetching EURUSD ticks for 2024-03-05 (2 of 3)`; stderr by default. */
    private val progress: (String) -> Unit = { System.err.println(it) },
) {
    /**
     * Fetches [symbol]'s days in `[from, to]` that are not on disk, one at a time, announcing each: a first run
     * can take minutes, and a day is committed to the store as soon as it lands, so an interrupted fetch keeps
     * the days it finished. A fetch that fails after its retries is incomplete data, reported like a hole.
     */
    private fun prefetch(
        symbol: String,
        from: LocalDate,
        to: LocalDate,
    ) {
        val stored =
            store
                .manifest(symbol)
                .ranges
                .flatMap { dayList(it) }
                .toSet()
        val missing =
            generateSequence(from) {
                it.plusDays(1)
            }.takeWhile { it <= to }.filter { it.toString() !in stored }.toList()
        try {
            missing.forEachIndexed { i, day ->
                progress("qkt: fetching $symbol ticks for $day (${i + 1} of ${missing.size})")
                val start = day.atStartOfDay(ZoneOffset.UTC).toInstant()
                store.prefetch(
                    MarketRequest(symbols = listOf(symbol), from = start, to = start.plusSeconds(DAY_SECONDS)),
                )
            }
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
        for (s in streams.distinctBy { it.bareSymbol }) {
            if (fetchEnabled) prefetch(s.bareSymbol, from, to)

            var report = TickCompletenessValidator.validate(store, s.bareSymbol, from, to, calendarFor(s.bareSymbol))

            val imported = ImportedTickDays(store.root.resolve("symbols").resolve(s.bareSymbol))
            if (report.hasHoles && fetchEnabled) {
                // A prior interrupted fetch can leave a day partial. Delete + refetch those once; an imported
                // day is the user's own data and is never replaced by a fetch, so its holes stand.
                for (hole in report.holes) if (!imported.contains(hole.day)) store.dropDay(s.bareSymbol, hole.day)
                prefetch(s.bareSymbol, from, to)
                report = TickCompletenessValidator.validate(store, s.bareSymbol, from, to, calendarFor(s.bareSymbol))
            }

            val requested = report.days.count { it.status != DayCompleteness.Status.NON_TRADING }
            val covered = report.days.count { it.covered }
            val short = report.days.filter { it.status == DayCompleteness.Status.SHORT }.map { it.day }
            val shortNote =
                when {
                    short.isEmpty() -> ""
                    short.size <= SHORT_LISTED -> " (short sessions, holiday hours: ${short.joinToString(", ")})"
                    else -> " (${short.size} short sessions, holiday hours, from ${short.first()})"
                }
            progress("qkt: tick coverage ${report.symbol} $covered/$requested trading days$shortNote")
            val stored = report.days.filter { store.dayFile(s.bareSymbol, it.day) != null }.map { it.day }
            imported.mixLine(s.bareSymbol, stored)?.let { progress("qkt: $it") }

            if (report.hasHoles && !allowIncomplete) {
                throw IncompleteDataException(
                    IncompleteTickMessage(store).describe(s, from, to, report, barsLineFor(s), fetchEnabled),
                )
            }
            if (report.hasHoles) {
                val holes = IncompleteTickMessage.collapseRanges(report.holes.map { it.day })
                progress("qkt: WARNING — running with incomplete data: $holes")
            }
        }
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
        const val SHORT_LISTED = 3
    }
}
