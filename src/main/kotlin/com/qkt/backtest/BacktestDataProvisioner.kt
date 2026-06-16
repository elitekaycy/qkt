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
    fun ensure(
        streams: List<ProvisionStream>,
        from: LocalDate,
        to: LocalDate,
        fetchEnabled: Boolean,
        allowIncomplete: Boolean,
        calendarFor: (String) -> TradingCalendar,
    ) {
        val fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant()
        val toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

        for (s in streams.distinctBy { it.bareSymbol }) {
            val request = MarketRequest(symbols = listOf(s.bareSymbol), from = fromInstant, to = toInstant)
            if (fetchEnabled) store.prefetch(request)

            var report = TickCompletenessValidator.validate(store, s.bareSymbol, from, to, calendarFor(s.bareSymbol))

            if (report.hasHoles && fetchEnabled) {
                // A prior interrupted fetch can leave a day partial. Delete + refetch those once.
                for (hole in report.holes) store.dropDay(s.bareSymbol, hole.day)
                store.prefetch(request)
                report = TickCompletenessValidator.validate(store, s.bareSymbol, from, to, calendarFor(s.bareSymbol))
            }

            if (report.hasHoles && !allowIncomplete) {
                throw IncompleteDataException(describe(report.symbol, report.holes))
            }
            if (report.hasHoles) {
                System.err.println(
                    "qkt: WARNING — running with incomplete data:\n${describe(report.symbol, report.holes)}",
                )
            }
        }
    }

    private fun describe(
        symbol: String,
        holes: List<DayCompleteness>,
    ): String =
        buildString {
            append("incomplete data for ").append(symbol).append(":\n")
            for (h in holes) {
                append("  ").append(h.day).append("  ").append(h.status.name.lowercase())
                if (h.emptyHours.isNotEmpty()) {
                    append(" (empty hours ").append(h.emptyHours.joinToString(",")).append(")")
                }
                append('\n')
            }
            append("  re-run with --allow-incomplete to proceed anyway")
        }
}
