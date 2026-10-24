package com.qkt.cli

import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountDirectory
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.futuresRootCalendar
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/** The calendar [calendar] chosen for [qktSymbol], and the step of [CalendarResolver] that chose it. */
internal data class CalendarChoice(
    val qktSymbol: String,
    val calendar: TradingCalendar,
    val source: String,
)

/**
 * The one rule that picks a symbol's trading calendar, in backtest and live alike:
 * 1. a futures contract's root `calendar:` in the instrument catalog;
 * 2. the venue's calendars, when a venue is known for the symbol ([venueOf]): its `calendars:` rules over its preset
 *    and default, exactly what the live account trades on;
 * 3. an option contract with no venue trades around the clock;
 * 4. qkt's [BuiltInCalendars].
 *
 * Live, the venue is the account serving the stream's prefix ([live]). A backtest names it with `--venue`, or takes
 * the broker the stream's prefix names when qkt knows that broker ([backtest]).
 */
internal class CalendarResolver(
    private val instruments: InstrumentRegistry?,
    /** Log each choice once at info: the daemon does, so its log names every symbol's calendar and source. */
    private val logChoices: Boolean = false,
    private val venueOf: (String) -> Venue?,
) {
    /** A venue's [name] and the [calendars] its account trades on. */
    data class Venue(
        val name: String,
        val calendars: SymbolCalendars,
    )

    private val choices = ConcurrentHashMap<String, CalendarChoice>()

    /** [qktSymbol]'s calendar. */
    fun calendarFor(qktSymbol: String): TradingCalendar = choose(qktSymbol).calendar

    /** [qktSymbol]'s calendar and where it came from. */
    fun choose(qktSymbol: String): CalendarChoice =
        choices.computeIfAbsent(qktSymbol) { symbol ->
            resolve(symbol).also {
                if (logChoices) log.info("calendar {} -> {} ({})", symbol, it.calendar.name, it.source)
            }
        }

    private fun resolve(qktSymbol: String): CalendarChoice {
        instruments?.futuresRootCalendar(qktSymbol)?.let { return CalendarChoice(qktSymbol, it, "futures root") }
        val bare = qktSymbol.substringAfter(':')
        venueOf(qktSymbol)?.let { venue ->
            val rule = venue.calendars.ruleFor(bare)
            val source = rule?.let { "${venue.name} calendars '${it.pattern}'" } ?: "${venue.name} default"
            return CalendarChoice(qktSymbol, venue.calendars.calendarFor(bare), source)
        }
        if (instruments?.options()?.optionRoot(qktSymbol) != null) {
            return CalendarChoice(qktSymbol, TradingCalendar.crypto(), "option contract, no venue")
        }
        val rule = BuiltInCalendars.rules.ruleFor(bare)
        val source = rule?.let { "built-in '${it.pattern}'" } ?: "built-in default"
        return CalendarChoice(qktSymbol, BuiltInCalendars.rules.calendarFor(bare), source)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CalendarResolver::class.java)

        /** Value of `--venue` that ignores every venue, so each symbol takes the built-in rules. */
        const val NO_VENUE = "none"

        /** The live rule: the venue is the account serving the stream's prefix. */
        fun live(
            accounts: AccountDirectory,
            instruments: InstrumentRegistry?,
        ): CalendarResolver =
            CalendarResolver(instruments, logChoices = true) { symbol ->
                accounts.forSymbol(symbol)?.let { Venue(it.config.name, it.tradingHours) }
            }

        /**
         * The backtest rule over the venues named in [names] (lower-case), whose calendars [venues] reads only when a
         * stream needs one: with [venue] (`--venue`) every symbol takes that venue's calendars, or none for
         * [NO_VENUE]; without it, the venue the stream's prefix names, and the built-in rules for a prefix that names
         * no venue (`BACKTEST:`).
         */
        fun backtest(
            names: Set<String>,
            venues: Lazy<Map<String, SymbolCalendars>>,
            venue: String?,
            instruments: InstrumentRegistry?,
        ): CalendarResolver {
            if (venue == NO_VENUE) return CalendarResolver(instruments) { null }
            if (venue != null) {
                val key = venue.lowercase()
                if (key !in names) {
                    throw BacktestContext.Companion.SetupError(
                        "unknown --venue '$venue' (known: ${(names.sorted() + NO_VENUE).joinToString()})",
                    )
                }
                val calendars =
                    venues.value[key]
                        ?: throw BacktestContext.Companion.SetupError("--venue '$venue' has no calendars qkt can read")
                return CalendarResolver(instruments) { Venue(key, calendars) }
            }
            return CalendarResolver(instruments) { symbol ->
                val prefix = symbol.substringBefore(':', missingDelimiterValue = "").lowercase()
                if (prefix !in names) null else venues.value[prefix]?.let { Venue(prefix, it) }
            }
        }
    }
}
